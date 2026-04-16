# Flow Code Reconstruction — Design Spec

## Problem

Current `MethodFlowGraph` loses expression-level detail: conditions in `if`/`while`/`for`, operator trees, cast expressions, lambdas, etc. The `TEMP_EXPR` node collapses entire expression trees into a single label string, which is effectively storing source text.

**Goal:** reconstruct semantically equivalent Java source code from a serialized flow graph, without storing any source text in the graph. The graph structure itself must encode enough information for code generation.

**Use case:** round-trip verification — `source → graph → reconstructed code → compare with original` — to prove that the graph correctly models the method.

## Constraints

- No source text stored anywhere in the graph (no `label = "a + b"`, no `conditionText`)
- Input is a serialized graph (not in-memory with AST access)
- Semantic equivalence required, not character-level identity
- Acceptable diff: variable names, ordering of independent statements, formatting, extra/missing parentheses
- All Java constructs: expressions, control flow, lambdas, streams, ternaries, casts, instanceof, arrays

## Approach: Expression Sub-Graph

Decompose every expression into fine-grained nodes connected by typed edges. Each syntactic operation becomes a separate `FlowNode`; operands are linked via edges. This turns the graph into a structure rich enough for tree-to-code generation.

---

## 1. Node Model Extensions

### New FlowNodeKind values

| Kind | Key attributes | Represents |
|---|---|---|
| `BINARY_OP` | `operator` (enum: PLUS, MINUS, MULT, DIV, MOD, AND, OR, EQ, NE, LT, GT, LE, GE, BIT_AND, BIT_OR, BIT_XOR, LSHIFT, RSHIFT, URSHIFT) | `x + y`, `a && b` |
| `UNARY_OP` | `operator` (enum: NOT, NEG, BIT_NOT, PRE_INC, PRE_DEC, POST_INC, POST_DEC) | `!flag`, `i++` |
| `CAST` | `targetType` (FQN) | `(String) obj` |
| `INSTANCEOF` | `targetType` (FQN), `patternVar` (nullable, for pattern matching) | `x instanceof String s` |
| `ARRAY_CREATE` | `elementType`, `dimensionCount` | `new int[n][m]` |
| `ARRAY_ACCESS` | — (array + index via edges) | `arr[i]` |
| `TERNARY` | — (condition, then, else via edges) | `x > 0 ? a : b` |
| `OBJECT_CREATE` | `constructorSignature` | `new ArrayList<>(10)` |
| `LAMBDA` | `paramNames`, `paramTypes` | `(x, y) -> x + y` |
| `METHOD_REF` | `targetType`, `methodName` | `String::valueOf` |
| `ASSIGN` | `assignOp` (enum: ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, ...) | `x += 5` |
| `THROW` | `exceptionType` (FQN) | `throw new IllegalArgumentException(...)` |

### Changes to existing node kinds

- `TEMP_EXPR` — **removed**. Replaced by `BINARY_OP`, `UNARY_OP`, `CAST`, etc.
- `LITERAL` — add `literalType` enum: `INT, LONG, DOUBLE, FLOAT, STRING, CHAR, BOOLEAN, NULL`. The literal value is stored in the `label` field as a canonical representation (e.g. `"42"`, `"true"`, `"null"`). This is data, not source text — a literal's value is its identity.
- `CALL` — add `callStyle`: `METHOD, STATIC, CONSTRUCTOR, CHAINED`
- `LOCAL_DEF` — add `isFinal`. Note: `LOCAL_DEF` represents a **declaration** (`int x = 5`). Reassignment of an existing variable (`x = 5`) uses `ASSIGN`.
- `BRANCH` — for `SWITCH`: each case is a control-dep child. Case value is stored as a `LITERAL` node linked by a `CONDITION` edge to a per-case BRANCH sub-node. Default case has no condition.

### CONDITION as a node kind — removed

The earlier draft listed `CONDITION` as both a node kind and an edge kind. This is unnecessary: the condition of a BRANCH/LOOP is simply an expression sub-tree (e.g. `BINARY_OP` root) connected by a `CONDITION` **edge**. No dedicated `CONDITION` node kind is needed.

---

## 2. Edge Model Extensions

### New FlowEdgeKind values

| Kind | Semantics | Example |
|---|---|---|
| `LEFT_OPERAND` | Left operand → BINARY_OP | `x` → `BINARY_OP(+)` |
| `RIGHT_OPERAND` | Right operand → BINARY_OP | `y` → `BINARY_OP(+)` |
| `UNARY_OPERAND` | Operand → UNARY_OP | `flag` → `UNARY_OP(NOT)` |
| `CONDITION` | Condition root → BRANCH/LOOP | expr sub-tree → `BRANCH(IF)` |
| `THEN_BRANCH` | BRANCH → first node of then-block | |
| `ELSE_BRANCH` | BRANCH → first node of else-block | |
| `CAST_OPERAND` | Operand → CAST | `obj` → `CAST(String)` |
| `INSTANCEOF_OPERAND` | Operand → INSTANCEOF | `x` → `INSTANCEOF(String)` |
| `ARRAY_REF` | Array → ARRAY_ACCESS | `arr` → `ARRAY_ACCESS` |
| `ARRAY_INDEX` | Index → ARRAY_ACCESS | `i` → `ARRAY_ACCESS` |
| `TERNARY_CONDITION` | Condition → TERNARY | |
| `TERNARY_THEN` | Then-value → TERNARY | |
| `TERNARY_ELSE` | Else-value → TERNARY | |
| `LAMBDA_BODY` | LAMBDA → root node of lambda body | |
| `LOOP_INIT` | Init expression → LOOP(FOR) | `int i = 0` → LOOP |
| `LOOP_UPDATE` | Update expression → LOOP(FOR) | `i++` → LOOP |
| `RECEIVER` | Receiver object → CALL | `obj` → `CALL(foo)` |
| `ASSIGN_TARGET` | ASSIGN → target (LOCAL_DEF / FIELD_WRITE) | |
| `ASSIGN_VALUE` | Value expression → ASSIGN | |

### Existing edges — unchanged

`DATA_DEP`, `ARG_PASS`, `CALL_RESULT_OF`, `RETURN_DEP`, `DEF_USE`, `PHI_INPUT`, `CONTROL_DEP` remain as-is.

### Edge direction convention

**Data-flow edges** (operands, values): operand → operator. Data flows in the direction of the edge, consistent with existing convention.

**Control-structure edges** (`THEN_BRANCH`, `ELSE_BRANCH`, `LAMBDA_BODY`): parent → child. The control node points to the block it governs. This is consistent with the existing `CONTROL_DEP` direction.

**Assignment edges**: `ASSIGN_VALUE` (value → ASSIGN) follows data-flow convention. `ASSIGN_TARGET` (ASSIGN → target) points from the assignment to the location being written — analogous to FIELD_WRITE semantics.

---

## 3. Reconstruction Algorithm — FlowCodeReconstructor

Input: `MethodFlowGraph`. Output: `String` (Java method source).

### Pass 1: Restore block tree (control-flow tree)

From the flat graph, rebuild nested block structure:

1. Collect all "statement-root" nodes: `LOCAL_DEF`, `ASSIGN`, `CALL`, `RETURN`, `BRANCH`, `LOOP`, `FIELD_WRITE`
2. Group by `enclosingControlId` — determines block membership
3. Nodes without `enclosingControlId` → top-level method body
4. For BRANCH: use `THEN_BRANCH` / `ELSE_BRANCH` edges to determine which nodes belong to which block
5. Within each block — sort by `sourceLine`

### Pass 2: Code generation (expression tree → text)

For each statement-node, recursively generate text by walking the expression sub-tree:

```
renderNode(node) → String:
  BINARY_OP   → maybeParens(renderNode(left) + " " + op + " " + renderNode(right))
  UNARY_OP    → prefix? op + renderNode(operand) : renderNode(operand) + op
  LITERAL     → node.label
  LOCAL_USE   → node.variableName
  LOCAL_DEF   → shortType(node.typeFqn) + " " + node.variableName + " = " + renderNode(value)
  CALL        → renderNode(receiver) + "." + methodName + "(" + renderArgs() + ")"
  CAST        → "(" + shortType(targetType) + ") " + renderNode(operand)
  TERNARY     → renderNode(cond) + " ? " + renderNode(then) + " : " + renderNode(else)
  ARRAY_ACCESS → renderNode(arr) + "[" + renderNode(index) + "]"
  LAMBDA      → "(" + params + ") -> " + renderBody(body)
  BRANCH(IF)  → "if (" + renderNode(condition) + ") {\n" + block(then) + "}" [+ else]
  LOOP(FOR)   → "for (" + init + "; " + cond + "; " + update + ") {\n" + block(body) + "}"
  RETURN      → "return " + renderNode(value) + ";"
```

**Operator precedence:** `renderNode` for `BINARY_OP` checks parent operator priority; wraps in parentheses if needed. Priority table is a static map.

**Call chains:** if CALL's receiver is a CALL_RESULT whose source is another CALL, render as `a.foo().bar()` without intermediate variables.

**Argument ordering:** restored from `ARG_PASS` edge label (argument index).

---

## 4. Verification — FlowRoundTripVerifier

### Pipeline

```
source.java
  → JavaParser AST
  → MethodFlowBuilder → MethodFlowGraph
  → FlowCodeReconstructor → reconstructed source
  → JavaParser AST (re-parse)
  → AST-diff with original
```

### Verification levels

**Level 1 — Parseability.** Reconstructed code must parse without errors via JavaParser. Failure = reconstruction is broken.

**Level 2 — Structural diff.** Compare two ASTs:
- Matching control structures: same if/for/while/try in same nesting order
- Matching method calls: same signatures in same order
- Matching return expressions

**Level 3 — Semantic equivalence.** Acceptable divergences:
- Local variable names
- Ordering of independent statements within a block
- `final` modifiers on locals
- Extra/missing parentheses (when precedence is unchanged)
- Formatting and whitespace

### Result model

```java
record VerificationResult(
    String methodSignature,
    boolean parseable,
    List<StructureDiff> diffs,
    double similarityScore,      // 0.0 — 1.0
    String originalCode,
    String reconstructedCode
)
```

### Similarity score — weighted

- 40% — control structure tree match (if/for/while/switch/try)
- 30% — method call match (signature + argument order)
- 20% — expression match (operators, literals)
- 10% — variable declaration match (types)

### Batch mode

`FlowRoundTripVerifier.verifyProject(sourceRoots)` — runs all methods, collects:
- Overall project score
- List of methods with lowest scores (for debugging)
- Distribution by level: how many parse, how many match structurally, how many are semantically equivalent

---

## 5. Changes to MethodFlowBuilder

### Core change: `analyzeExpr()` becomes recursive

Current: `a + b * c` → `TEMP_EXPR(label="a + b * c")` (one node)

New: `a + b * c` →
```
BINARY_OP(PLUS)
  ├── LEFT_OPERAND ← LOCAL_USE(a)
  └── RIGHT_OPERAND ← BINARY_OP(MULT)
                         ├── LEFT_OPERAND ← LOCAL_USE(b)
                         └── RIGHT_OPERAND ← LOCAL_USE(c)
```

### JavaParser expression type mapping

- `BinaryExpr` → `BINARY_OP` + recurse on operands
- `UnaryExpr` → `UNARY_OP` + recurse on operand
- `CastExpr` → `CAST` + recurse
- `ConditionalExpr` → `TERNARY` + three edges
- `MethodCallExpr` → `CALL` (existing) + `RECEIVER` edge
- `ObjectCreationExpr` → `OBJECT_CREATE`
- `ArrayAccessExpr` → `ARRAY_ACCESS` + two edges
- `LambdaExpr` → `LAMBDA` + recursive `build()` for body
- `InstanceOfExpr` → `INSTANCEOF`

### Conditions in BRANCH/LOOP

Instead of ignoring the condition, call `analyzeExpr(condition)` and link the root of the sub-tree to BRANCH/LOOP via `CONDITION` edge.

### For-loop init and update

Call `analyzeExpr()` for init and update expressions, link via `LOOP_INIT` / `LOOP_UPDATE` edges.

---

## 6. Impact on Existing Analyses

### BackwardSlicer

Continues to work. Walks `DATA_DEP`, `ARG_PASS`, `DEF_USE`, `PHI_INPUT` edges. New expression nodes will be included in slices as intermediaries — correct behavior, they are part of data-flow. One addition: when traversing a `CONDITION` edge, include the entire condition sub-tree in the slice.

### FlowChainExtractor

Minimal change. Currently skips `TEMP_EXPR`. Must analogously skip `BINARY_OP`, `UNARY_OP`, `CAST`, and other expression-level nodes (they are "technical" for chain extraction purposes).

### FlowJsonExporter / FlowHtmlExporter

New node/edge kinds will be serialized automatically if exporters work generically with `FlowNodeKind` / `FlowEdgeKind`. Verify no hardcoded kind lists exist.
