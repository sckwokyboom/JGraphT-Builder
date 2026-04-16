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
| `ARRAY_CREATE` | `elementType` | `new int[n][m]` |
| `ARRAY_ACCESS` | — (array + index via edges) | `arr[i]` |
| `TERNARY` | — (condition, then, else via edges) | `x > 0 ? a : b` |
| `OBJECT_CREATE` | `constructorSignature` | `new ArrayList<>(10)` |
| `LAMBDA` | `paramNames`, `paramTypes` | `(x, y) -> x + y` |
| `METHOD_REF` | `targetType`, `methodName` | `String::valueOf` |
| `ASSIGN` | `assignOp` (enum: ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, ...) | `x += 5` |
| `THROW` | — (exception expression linked via data edge) | `throw new IllegalArgumentException(...)` |
| `BREAK` | `targetLabel` (nullable — for labeled break) | `break;`, `break outer;` |
| `CONTINUE` | `targetLabel` (nullable — for labeled continue) | `continue;`, `continue outer;` |
| `ASSERT` | — (condition + optional message via edges) | `assert x > 0 : "msg"` |
| `SYNCHRONIZED` | — (lock expression via edge, body via CONTROL_DEP) | `synchronized(lock) { ... }` |
| `SUPER_REF` | — (analogous to THIS_REF but for super) | `super.foo()` |
| `SWITCH_CASE` | `isDefault` (boolean) | `case 1:`, `default:` |
| `YIELD` | — (value via data edge) | `yield x;` |

### Changes to existing node kinds

- `TEMP_EXPR` — **removed**. Replaced by `BINARY_OP`, `UNARY_OP`, `CAST`, etc.
- `LITERAL` — add `literalType` enum: `INT, LONG, DOUBLE, FLOAT, STRING, CHAR, BOOLEAN, NULL`. The literal value is stored in the `label` field as a canonical representation (e.g. `"42"`, `"true"`, `"null"`). This is data, not source text — a literal's value is its identity.
- `CALL` — add `callStyle` enum: `METHOD, STATIC, CONSTRUCTOR, CHAINED`. Add `methodName` attribute (string) — the simple name of the method being called (e.g. `"add"`, `"toString"`). For constructors, `methodName` = `"<init>"`.
- `LOCAL_DEF` — add `isFinal`. Note: `LOCAL_DEF` represents a **declaration** (`int x = 5`). Reassignment of an existing variable (`x = 5`) uses `ASSIGN`. Current builder creates another `LOCAL_DEF` for reassignment — this must change.
- `BRANCH` — for `SWITCH`: each case is represented as a `SWITCH_CASE` child node connected by `CONTROL_DEP`. Case values are `LITERAL` nodes linked to `SWITCH_CASE` by `CONDITION` edges. Default case has `isDefault = true` and no `CONDITION` edge. Fall-through is encoded by consecutive `SWITCH_CASE` nodes sharing the same `enclosingControlId`.
- `LOOP` — already has `controlSubtype` (FOR, FOREACH, WHILE, DO). Rendering must handle all four variants.
- `FIELD_READ` / `FIELD_WRITE` — already exist but need `fieldName` attribute (string) for reconstruction. Currently rely on labels.
- `RETURN` — may have no value (void return). The renderer must handle both `return expr;` and `return;`.
- `FlowNode` schema change: add a generic `Map<String, String> attributes` to hold kind-specific data (`operator`, `literalType`, `assignOp`, `methodName`, `targetType`, `patternVar`, `targetLabel`, `fieldName`, `isDefault`, `isFinal`, `callStyle`, `elementType`, `paramNames`, `paramTypes`). This avoids adding 15+ nullable fields to FlowNode.

### Node ordering

Current builder assigns `sourceLine = -1` to control nodes (BRANCH, LOOP, MERGE). For reconstruction, all nodes must have a valid `sourceLine`. Additionally, add `stmtOrdinal` (int) — a monotonically increasing counter assigned during build. This provides a total ordering of statements within a method, independent of source line (which may be shared by multiple nodes).

### CONDITION as a node kind — removed

The condition of a BRANCH/LOOP is simply an expression sub-tree (e.g. `BINARY_OP` root) connected by a `CONDITION` **edge**. No dedicated `CONDITION` node kind is needed.

---

## 2. Edge Model Extensions

### New FlowEdgeKind values

| Kind | Semantics | Example |
|---|---|---|
| `LEFT_OPERAND` | Left operand → BINARY_OP | `x` → `BINARY_OP(+)` |
| `RIGHT_OPERAND` | Right operand → BINARY_OP | `y` → `BINARY_OP(+)` |
| `UNARY_OPERAND` | Operand → UNARY_OP | `flag` → `UNARY_OP(NOT)` |
| `CONDITION` | Condition root → BRANCH/LOOP/ASSERT | expr sub-tree → `BRANCH(IF)` |
| `THEN_BRANCH` | BRANCH → first stmt of then-block | structural edge for block membership |
| `ELSE_BRANCH` | BRANCH → first stmt of else-block | structural edge for block membership |
| `CAST_OPERAND` | Operand → CAST | `obj` → `CAST(String)` |
| `INSTANCEOF_OPERAND` | Operand → INSTANCEOF | `x` → `INSTANCEOF(String)` |
| `ARRAY_REF` | Array → ARRAY_ACCESS | `arr` → `ARRAY_ACCESS` |
| `ARRAY_INDEX` | Index → ARRAY_ACCESS | `i` → `ARRAY_ACCESS` |
| `ARRAY_DIM` | Dimension expr → ARRAY_CREATE (label = dim index "0", "1", ...) | `n` → `ARRAY_CREATE` |
| `TERNARY_CONDITION` | Condition → TERNARY | |
| `TERNARY_THEN` | Then-value → TERNARY | |
| `TERNARY_ELSE` | Else-value → TERNARY | |
| `LAMBDA_BODY` | LAMBDA → root node of lambda body | |
| `LOOP_INIT` | Init expression → LOOP(FOR) | `int i = 0` → LOOP |
| `LOOP_UPDATE` | Update expression → LOOP(FOR) | `i++` → LOOP |
| `LOOP_ITERABLE` | Iterable expression → LOOP(FOREACH) | `list` → LOOP |
| `RECEIVER` | Receiver object → CALL | `obj` → `CALL(foo)` |
| `ASSIGN_TARGET` | ASSIGN → target (LOCAL_DEF / FIELD_WRITE / ARRAY_ACCESS) | |
| `ASSIGN_VALUE` | Value expression → ASSIGN | |
| `THROW_VALUE` | Exception expression → THROW | `new IAE()` → THROW |
| `ASSERT_MESSAGE` | Message expression → ASSERT | `"msg"` → ASSERT |
| `SYNC_LOCK` | Lock expression → SYNCHRONIZED | `lock` → SYNCHRONIZED |
| `CATCH_PARAM` | BRANCH(TRY) → catch parameter node | catch variable declaration |
| `TRY_RESOURCE` | BRANCH(TRY) → resource node (label = resource index) | try-with-resources |
| `FINALLY_BODY` | BRANCH(TRY) → first stmt of finally block | |
| `YIELD_VALUE` | Value → YIELD | `expr` → YIELD |

### Existing edges — unchanged

`DATA_DEP`, `ARG_PASS`, `CALL_RESULT_OF`, `RETURN_DEP`, `DEF_USE`, `PHI_INPUT`, `CONTROL_DEP` remain as-is.

### Edge direction convention

**Data-flow edges** (operands, values): operand → operator. Data flows in the direction of the edge, consistent with existing convention.

**Control-structure edges** (`THEN_BRANCH`, `ELSE_BRANCH`, `LAMBDA_BODY`, `FINALLY_BODY`): parent → child. The control node points to the block it governs. This is consistent with the existing `CONTROL_DEP` direction.

**Assignment edges**: `ASSIGN_VALUE` (value → ASSIGN) follows data-flow convention. `ASSIGN_TARGET` (ASSIGN → target) points from the assignment to the location being written — analogous to FIELD_WRITE semantics. Target includes `ARRAY_ACCESS` for array element assignments.

---

## 3. Reconstruction Algorithm — FlowCodeReconstructor

Input: `MethodFlowGraph`. Output: `String` (Java method source).

### Pass 1: Restore block tree (control-flow tree)

From the flat graph, rebuild nested block structure:

1. Collect all "statement-root" nodes: `LOCAL_DEF`, `ASSIGN`, `CALL` (without incoming `RECEIVER`), `RETURN`, `BRANCH`, `LOOP`, `FIELD_WRITE`, `THROW`, `BREAK`, `CONTINUE`, `ASSERT`, `SYNCHRONIZED`, `YIELD`. Exclude nodes that are targets of `ASSIGN_TARGET` edges (they are owned by the ASSIGN, not independent statements).
2. Group by `enclosingControlId` — determines block membership.
3. Nodes without `enclosingControlId` → top-level method body.
4. For BRANCH(IF): children with THEN_BRANCH/ELSE_BRANCH edges determine block membership. Children via CONTROL_DEP without THEN/ELSE classification belong to the branch body.
5. For BRANCH(TRY): children are grouped into try-body, catch-blocks (via CATCH_PARAM edges), and finally-block (via FINALLY_BODY edge).
6. For BRANCH(SWITCH): children are SWITCH_CASE nodes, each governing its own body via CONTROL_DEP.
7. Within each block — sort by `stmtOrdinal` (primary), `sourceLine` (fallback).

### Pass 2: Code generation (expression tree → text)

For each statement-node, recursively generate text by walking the expression sub-tree:

```
renderNode(node) → String:
  BINARY_OP    → maybeParens(renderNode(left) + " " + op + " " + renderNode(right))
  UNARY_OP     → prefix? op + renderNode(operand) : renderNode(operand) + op
  LITERAL      → node.label  (canonical value: "42", "\"hello\"", "true", "null")
  LOCAL_USE    → node.variableName
  LOCAL_DEF    → typeFqn + " " + variableName [+ " = " + renderNode(value)]
  CALL         → renderReceiver() + methodName + "(" + renderArgs() + ")"
  CALL_RESULT  → renderNode(sourceCall)  (transparent — delegates to the CALL)
  CAST         → "(" + targetType + ") " + renderNode(operand)
  TERNARY      → renderNode(cond) + " ? " + renderNode(then) + " : " + renderNode(else)
  ARRAY_ACCESS → renderNode(arr) + "[" + renderNode(index) + "]"
  ARRAY_CREATE → "new " + elementType + renderDimensions()
  OBJECT_CREATE → "new " + typeName + "(" + renderArgs() + ")"
  LAMBDA       → "(" + params + ") -> " + renderBody(body)
  METHOD_REF   → targetType + "::" + methodName
  INSTANCEOF   → renderNode(operand) + " instanceof " + targetType [+ " " + patternVar]
  FIELD_READ   → renderNode(receiver) + "." + fieldName
  FIELD_WRITE  → renderNode(receiver) + "." + fieldName + " = " + renderNode(value)
  THIS_REF     → "this"
  SUPER_REF    → "super"
  PARAM        → (not rendered as statement — used as expression leaf)
  MERGE_VALUE  → renderNode(dominantInput)  (transparent — picks the phi input)
  ASSIGN       → renderNode(target) + " " + assignOp + " " + renderNode(value)
  RETURN       → hasValue? "return " + renderNode(value) + ";" : "return;"
  THROW        → "throw " + renderNode(throwValue) + ";"
  BREAK        → "break" [+ " " + targetLabel] + ";"
  CONTINUE     → "continue" [+ " " + targetLabel] + ";"
  ASSERT       → "assert " + renderNode(condition) [+ " : " + renderNode(message)] + ";"
  YIELD        → "yield " + renderNode(value) + ";"

  BRANCH(IF)   → "if (" + renderNode(condition) + ") {\n" + block(then) + "}"
                   [+ " else {\n" + block(else) + "}"]
  BRANCH(SWITCH) → "switch (" + renderNode(selector) + ") {\n" + renderCases() + "}"
  BRANCH(TRY)  → "try " + renderResources() + "{\n" + block(try) + "}"
                   + renderCatches() [+ " finally {\n" + block(finally) + "}"]

  LOOP(FOR)     → "for (" + renderNode(init) + "; " + renderNode(cond) + "; "
                     + renderNode(update) + ") {\n" + block(body) + "}"
  LOOP(FOREACH) → "for (" + iterVarType + " " + iterVarName + " : "
                     + renderNode(iterable) + ") {\n" + block(body) + "}"
  LOOP(WHILE)   → "while (" + renderNode(condition) + ") {\n" + block(body) + "}"
  LOOP(DO)      → "do {\n" + block(body) + "} while (" + renderNode(condition) + ");"

  SYNCHRONIZED → "synchronized (" + renderNode(lock) + ") {\n" + block(body) + "}"
```

**Operator precedence:** `renderNode` for `BINARY_OP` checks parent operator priority and associativity; wraps in parentheses if needed. Priority table and associativity are static maps. Left-associative operators need parens on the right operand when same-precedence; right-associative (assignment) need parens on the left.

**Call chains:** if CALL's receiver is a CALL_RESULT whose source is another CALL, render as `a.foo().bar()` without intermediate variables. Static calls render as `ClassName.method(...)`.

**Argument ordering:** restored from `ARG_PASS` edge label. Labels use format `"0"`, `"1"`, etc. (numeric index). Current builder uses `"arg[0]"` format — this must be normalized to plain numeric index.

**Type rendering:** use FQN throughout for parseability. The reconstructed code is meant for verification, not human reading. FQN avoids the need for import context.

**CALL_RESULT and MERGE_VALUE transparency:** these nodes are not rendered directly. `CALL_RESULT` delegates to its source `CALL`. `MERGE_VALUE` picks one of its `PHI_INPUT` sources (the first/dominant one) — this is a semantic approximation but sufficient for verification, since the phi exists only at branch/loop merge points.

---

## 4. Verification — FlowRoundTripVerifier

### Pipeline

```
source.java
  → JavaParser AST
  → MethodFlowBuilder → MethodFlowGraph
  → FlowCodeReconstructor → reconstructed method body
  → wrap in synthetic class: "class __Verify { <reconstructed method> }"
  → JavaParser AST (re-parse)
  → AST-diff with original method AST
```

The synthetic class wrapper is necessary because JavaParser cannot parse a standalone method body. The wrapper is stripped after parsing — only the method AST is compared.

### Verification levels

**Level 1 — Parseability.** Reconstructed code must parse without errors via JavaParser. Failure = reconstruction is broken.

**Level 2 — Structural diff.** Compare two ASTs:
- Matching control structures: same if/for/while/try/switch in same nesting order
- Matching method calls: same signatures in same order
- Matching return expressions
- Matching throw, break, continue, assert, synchronized statements

**Level 3 — Semantic equivalence.** Acceptable divergences:
- Local variable names
- Ordering of independent statements within a block
- `final` modifiers on locals
- Extra/missing parentheses (when precedence is unchanged)
- Formatting and whitespace
- Short type names vs FQN (e.g. `String` vs `java.lang.String`)

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
- `MethodCallExpr` → `CALL` (existing) + `RECEIVER` edge + `methodName` attr
- `ObjectCreationExpr` → `OBJECT_CREATE`
- `ArrayAccessExpr` → `ARRAY_ACCESS` + two edges
- `ArrayCreationExpr` → `ARRAY_CREATE` + `ARRAY_DIM` edges for each dimension
- `LambdaExpr` → `LAMBDA` + recursive `build()` for body
- `MethodReferenceExpr` → `METHOD_REF` (currently opaque — must be modeled)
- `InstanceOfExpr` → `INSTANCEOF`
- `SuperExpr` → `SUPER_REF` (new node kind, currently mapped to THIS_REF)

### Statement type mapping (new/changed)

- `ThrowStmt` → `THROW` node + `THROW_VALUE` edge to exception expression (currently only analyzes the expression, creates no throw node)
- `BreakStmt` → `BREAK` node with `targetLabel` attribute
- `ContinueStmt` → `CONTINUE` node with `targetLabel` attribute
- `AssertStmt` → `ASSERT` node + `CONDITION` edge + optional `ASSERT_MESSAGE` edge
- `SynchronizedStmt` → `SYNCHRONIZED` node + `SYNC_LOCK` edge + body via CONTROL_DEP
- `YieldStmt` → `YIELD` node + `YIELD_VALUE` edge
- Variable reassignment → `ASSIGN` node (currently creates another `LOCAL_DEF` — must change)

### Conditions in BRANCH/LOOP

Instead of ignoring the condition, call `analyzeExpr(condition)` and link the root of the sub-tree to BRANCH/LOOP via `CONDITION` edge.

### For-loop init and update

Call `analyzeExpr()` for init and update expressions, link via `LOOP_INIT` / `LOOP_UPDATE` edges.

### Foreach loop iterable

Call `analyzeExpr()` for the iterable, link via `LOOP_ITERABLE` edge. Store iteration variable type and name in the LOOP node attributes.

### Try/catch/finally

- `TryStmt` → `BRANCH(TRY)` node (already exists)
- Resources: each resource → node linked by `TRY_RESOURCE` edge (label = index)
- Catch clauses: each catch parameter → `LOCAL_DEF` node linked by `CATCH_PARAM` edge. Catch body statements get `enclosingControlId` pointing to this BRANCH.
- Finally block: first stmt linked by `FINALLY_BODY` edge. Statements get `enclosingControlId`.

### Switch cases

- `SwitchStmt`/`SwitchExpr` → `BRANCH(SWITCH)` (already exists)
- Each case → `SWITCH_CASE` node with `isDefault` attribute, linked by `CONTROL_DEP` from BRANCH
- Case label values → `LITERAL` nodes linked by `CONDITION` edge from `SWITCH_CASE`
- Case body statements → `enclosingControlId` pointing to `SWITCH_CASE`
- `YieldStmt` → `YIELD` node (for switch expressions)

### Source line and ordering

All nodes must have a valid `sourceLine` (current builder sets `-1` for control nodes — fix this by extracting line from the AST node's begin position). Additionally, assign `stmtOrdinal` from a method-level counter that increments for each statement processed. This ensures total ordering even when multiple statements share a source line.

### FlowNode schema

Add `Map<String, String> attributes` field to `FlowNode` for kind-specific data. This avoids proliferating nullable fields. Attributes are serialized as part of JSON export. Access via typed getters: `node.attr("operator")`, `node.attrInt("stmtOrdinal")`, etc.

### ARG_PASS label normalization

Change argument labels from `"arg[0]"`, `"arg[1]"` to plain `"0"`, `"1"`. Receiver label changes from `"receiver"` to using the dedicated `RECEIVER` edge kind instead.

---

## 6. Impact on Existing Analyses

### BackwardSlicer

Continues to work. Walks `DATA_DEP`, `ARG_PASS`, `DEF_USE`, `PHI_INPUT` edges. New expression nodes will be included in slices as intermediaries — correct behavior, they are part of data-flow. One addition: when traversing a `CONDITION` edge, include the entire condition sub-tree in the slice.

### FlowChainExtractor

Minimal change. Currently skips `TEMP_EXPR`. Must analogously skip `BINARY_OP`, `UNARY_OP`, `CAST`, `INSTANCEOF`, `TERNARY`, `ARRAY_ACCESS`, `ARRAY_CREATE`, and other expression-level nodes (they are "technical" for chain extraction purposes). The documented technical-node set in FlowChainExtractor must be updated.

### FlowJsonExporter / FlowHtmlExporter

New node/edge kinds will be serialized automatically if exporters work generically with `FlowNodeKind` / `FlowEdgeKind`. The `attributes` map serializes as a nested JSON object. Verify: `prefixOf()` in MethodFlowBuilder switches over every FlowNodeKind — must add cases for new kinds.

### MethodFlowBuilder.prefixOf()

This method assigns ID prefixes for each node kind. Must add entries for all new kinds: `BINARY_OP` → `"BINOP"`, `UNARY_OP` → `"UNOP"`, `CAST` → `"CAST"`, etc.
