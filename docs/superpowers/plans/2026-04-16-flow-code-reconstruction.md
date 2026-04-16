# Flow Code Reconstruction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reconstruct semantically equivalent Java source code from serialized flow graphs, enabling round-trip verification of graph correctness.

**Architecture:** Extend the flow graph model with expression-level nodes (BINARY_OP, UNARY_OP, CAST, etc.) and structural edges, refactor MethodFlowBuilder to produce fine-grained expression sub-trees instead of collapsing to TEMP_EXPR, then build a FlowCodeReconstructor that walks the enriched graph to emit Java source and a FlowRoundTripVerifier for automated comparison.

**Tech Stack:** Java 25 (preview), JGraphT 1.5.2, JavaParser 3.26.4, JUnit 5, AssertJ

---

## File Structure

### New files
- `src/main/java/com/github/sckwoky/typegraph/flow/model/BinaryOperator.java` — enum mapping JavaParser binary operators
- `src/main/java/com/github/sckwoky/typegraph/flow/model/UnaryOperator.java` — enum mapping JavaParser unary operators  
- `src/main/java/com/github/sckwoky/typegraph/flow/model/AssignOperator.java` — enum for assignment operators
- `src/main/java/com/github/sckwoky/typegraph/flow/model/LiteralType.java` — enum for literal kinds
- `src/main/java/com/github/sckwoky/typegraph/flow/model/CallStyle.java` — enum for call styles
- `src/main/java/com/github/sckwoky/typegraph/flow/FlowCodeReconstructor.java` — graph → Java source
- `src/main/java/com/github/sckwoky/typegraph/flow/FlowRoundTripVerifier.java` — round-trip verification
- `src/test/java/com/github/sckwoky/typegraph/flow/FlowCodeReconstructorTest.java` — reconstructor tests
- `src/test/java/com/github/sckwoky/typegraph/flow/FlowRoundTripVerifierTest.java` — verifier tests
- `src/test/resources/fixtures/com/example/ReconstructionFixture.java` — test fixture with all Java constructs

### Modified files
- `src/main/java/com/github/sckwoky/typegraph/flow/model/FlowNodeKind.java` — add new node kinds
- `src/main/java/com/github/sckwoky/typegraph/flow/model/FlowEdgeKind.java` — add new edge kinds
- `src/main/java/com/github/sckwoky/typegraph/flow/model/FlowNode.java` — add attributes map + stmtOrdinal
- `src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java` — expression sub-graph building
- `src/main/java/com/github/sckwoky/typegraph/flow/BackwardSlicer.java` — handle CONDITION edges
- `src/main/java/com/github/sckwoky/typegraph/flow/FlowChainExtractor.java` — skip new expression nodes
- `src/main/java/com/github/sckwoky/typegraph/export/FlowJsonExporter.java` — serialize attributes map
- `src/test/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilderTest.java` — tests for new node/edge kinds

---

## Phase 1: Model Extensions

### Task 1: Add new enums for expression attributes

**Files:**
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/model/BinaryOperator.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/model/UnaryOperator.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/model/AssignOperator.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/model/LiteralType.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/model/CallStyle.java`

- [ ] **Step 1: Create BinaryOperator enum**

```java
package com.github.sckwoky.typegraph.flow.model;

import com.github.javaparser.ast.expr.BinaryExpr;

public enum BinaryOperator {
    PLUS("+", 11, true),
    MINUS("-", 11, true),
    MULT("*", 12, true),
    DIV("/", 12, true),
    MOD("%", 12, true),
    AND("&&", 4, true),
    OR("||", 3, true),
    EQ("==", 8, true),
    NE("!=", 8, true),
    LT("<", 9, true),
    GT(">", 9, true),
    LE("<=", 9, true),
    GE(">=", 9, true),
    BIT_AND("&", 7, true),
    BIT_OR("|", 5, true),
    BIT_XOR("^", 6, true),
    LSHIFT("<<", 10, true),
    RSHIFT(">>", 10, true),
    URSHIFT(">>>", 10, true);

    private final String symbol;
    private final int precedence;
    private final boolean leftAssociative;

    BinaryOperator(String symbol, int precedence, boolean leftAssociative) {
        this.symbol = symbol;
        this.precedence = precedence;
        this.leftAssociative = leftAssociative;
    }

    public String symbol() { return symbol; }
    public int precedence() { return precedence; }
    public boolean leftAssociative() { return leftAssociative; }

    public static BinaryOperator fromJavaParser(BinaryExpr.Operator op) {
        return switch (op) {
            case PLUS -> PLUS;
            case MINUS -> MINUS;
            case MULTIPLY -> MULT;
            case DIVIDE -> DIV;
            case REMAINDER -> MOD;
            case AND -> AND;
            case OR -> OR;
            case EQUALS -> EQ;
            case NOT_EQUALS -> NE;
            case LESS -> LT;
            case GREATER -> GT;
            case LESS_EQUALS -> LE;
            case GREATER_EQUALS -> GE;
            case BINARY_AND -> BIT_AND;
            case BINARY_OR -> BIT_OR;
            case XOR -> BIT_XOR;
            case LEFT_SHIFT -> LSHIFT;
            case SIGNED_RIGHT_SHIFT -> RSHIFT;
            case UNSIGNED_RIGHT_SHIFT -> URSHIFT;
        };
    }
}
```

- [ ] **Step 2: Create UnaryOperator enum**

```java
package com.github.sckwoky.typegraph.flow.model;

import com.github.javaparser.ast.expr.UnaryExpr;

public enum UnaryOperator {
    NOT("!", true),
    NEG("-", true),
    BIT_NOT("~", true),
    PRE_INC("++", true),
    PRE_DEC("--", true),
    POST_INC("++", false),
    POST_DEC("--", false),
    PLUS("+", true);

    private final String symbol;
    private final boolean prefix;

    UnaryOperator(String symbol, boolean prefix) {
        this.symbol = symbol;
        this.prefix = prefix;
    }

    public String symbol() { return symbol; }
    public boolean prefix() { return prefix; }

    public static UnaryOperator fromJavaParser(UnaryExpr.Operator op) {
        return switch (op) {
            case LOGICAL_COMPLEMENT -> NOT;
            case MINUS -> NEG;
            case BITWISE_COMPLEMENT -> BIT_NOT;
            case PREFIX_INCREMENT -> PRE_INC;
            case PREFIX_DECREMENT -> PRE_DEC;
            case POSTFIX_INCREMENT -> POST_INC;
            case POSTFIX_DECREMENT -> POST_DEC;
            case PLUS -> PLUS;
        };
    }
}
```

- [ ] **Step 3: Create AssignOperator, LiteralType, CallStyle enums**

`AssignOperator.java`:
```java
package com.github.sckwoky.typegraph.flow.model;

import com.github.javaparser.ast.expr.AssignExpr;

public enum AssignOperator {
    ASSIGN("="),
    PLUS_ASSIGN("+="),
    MINUS_ASSIGN("-="),
    MULT_ASSIGN("*="),
    DIV_ASSIGN("/="),
    MOD_ASSIGN("%="),
    AND_ASSIGN("&="),
    OR_ASSIGN("|="),
    XOR_ASSIGN("^="),
    LSHIFT_ASSIGN("<<="),
    RSHIFT_ASSIGN(">>="),
    URSHIFT_ASSIGN(">>>=");

    private final String symbol;
    AssignOperator(String symbol) { this.symbol = symbol; }
    public String symbol() { return symbol; }

    public static AssignOperator fromJavaParser(AssignExpr.Operator op) {
        return switch (op) {
            case ASSIGN -> ASSIGN;
            case PLUS -> PLUS_ASSIGN;
            case MINUS -> MINUS_ASSIGN;
            case MULTIPLY -> MULT_ASSIGN;
            case DIVIDE -> DIV_ASSIGN;
            case REMAINDER -> MOD_ASSIGN;
            case BINARY_AND -> AND_ASSIGN;
            case BINARY_OR -> OR_ASSIGN;
            case XOR -> XOR_ASSIGN;
            case LEFT_SHIFT -> LSHIFT_ASSIGN;
            case SIGNED_RIGHT_SHIFT -> RSHIFT_ASSIGN;
            case UNSIGNED_RIGHT_SHIFT -> URSHIFT_ASSIGN;
        };
    }
}
```

`LiteralType.java`:
```java
package com.github.sckwoky.typegraph.flow.model;

public enum LiteralType {
    INT, LONG, DOUBLE, FLOAT, STRING, CHAR, BOOLEAN, NULL
}
```

`CallStyle.java`:
```java
package com.github.sckwoky.typegraph.flow.model;

public enum CallStyle {
    METHOD, STATIC, CONSTRUCTOR, CHAINED
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/model/BinaryOperator.java \
        src/main/java/com/github/sckwoky/typegraph/flow/model/UnaryOperator.java \
        src/main/java/com/github/sckwoky/typegraph/flow/model/AssignOperator.java \
        src/main/java/com/github/sckwoky/typegraph/flow/model/LiteralType.java \
        src/main/java/com/github/sckwoky/typegraph/flow/model/CallStyle.java
git commit -m "feat: add expression attribute enums (BinaryOperator, UnaryOperator, AssignOperator, LiteralType, CallStyle)"
```

---

### Task 2: Extend FlowNodeKind and FlowEdgeKind

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/model/FlowNodeKind.java`
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/model/FlowEdgeKind.java`

- [ ] **Step 1: Add new FlowNodeKind values**

Replace the full enum body in `FlowNodeKind.java`:
```java
public enum FlowNodeKind {
    PARAM,         // method parameter (input)
    THIS_REF,      // reference to enclosing instance
    SUPER_REF,     // reference to super
    FIELD_READ,    // reading a field
    FIELD_WRITE,   // assignment to a field
    LOCAL_DEF,     // versioned definition of a local variable (declaration)
    LOCAL_USE,     // use of a local variable
    MERGE_VALUE,   // phi-like merge of variable definitions across branches/loops
    CALL,          // method/constructor invocation operation
    CALL_RESULT,   // value produced by a CALL
    RETURN,        // return statement
    BRANCH,        // control split (if/switch/try/ternary) — see controlSubtype
    MERGE,         // control merge after BRANCH
    LOOP,          // loop summary node — see controlSubtype (for/foreach/while/do)
    LITERAL,       // literal/constant value

    // Expression-level nodes (replace TEMP_EXPR)
    BINARY_OP,     // binary operator expression (a + b, x && y)
    UNARY_OP,      // unary operator expression (!flag, i++)
    CAST,          // type cast expression ((String) obj)
    INSTANCEOF,    // instanceof check (x instanceof String s)
    ARRAY_CREATE,  // array creation (new int[n])
    ARRAY_ACCESS,  // array element access (arr[i])
    TERNARY,       // ternary expression (x ? a : b)
    OBJECT_CREATE, // object construction (new Foo(...))
    LAMBDA,        // lambda expression ((x) -> ...)
    METHOD_REF,    // method reference (Cls::method)
    ASSIGN,        // assignment/reassignment (x = 5, x += 1)

    // Statement-level nodes
    THROW,         // throw statement
    BREAK,         // break statement
    CONTINUE,      // continue statement
    ASSERT,        // assert statement
    SYNCHRONIZED,  // synchronized block
    SWITCH_CASE,   // single case in a switch
    YIELD          // yield in switch expression
}
```

- [ ] **Step 2: Add new FlowEdgeKind values**

Replace the full enum body in `FlowEdgeKind.java`:
```java
public enum FlowEdgeKind {
    // Existing
    DATA_DEP,           // generic data dependency between values
    ARG_PASS,           // value flowing into a CALL as an argument (label = arg index)
    CALL_RESULT_OF,     // CALL → CALL_RESULT structural edge
    RETURN_DEP,         // value flowing into a RETURN
    DEF_USE,            // LOCAL_DEF → LOCAL_USE
    PHI_INPUT,          // version → MERGE_VALUE
    CONTROL_DEP,        // BRANCH/LOOP → child node inside its control region

    // Expression operand edges
    LEFT_OPERAND,       // left operand → BINARY_OP
    RIGHT_OPERAND,      // right operand → BINARY_OP
    UNARY_OPERAND,      // operand → UNARY_OP
    CAST_OPERAND,       // operand → CAST
    INSTANCEOF_OPERAND, // operand → INSTANCEOF

    // Control structure edges
    CONDITION,          // condition expression root → BRANCH/LOOP/ASSERT
    THEN_BRANCH,        // BRANCH → first stmt of then-block
    ELSE_BRANCH,        // BRANCH → first stmt of else-block

    // Array edges
    ARRAY_REF,          // array → ARRAY_ACCESS
    ARRAY_INDEX,        // index → ARRAY_ACCESS
    ARRAY_DIM,          // dimension expr → ARRAY_CREATE (label = dim index)

    // Ternary edges
    TERNARY_CONDITION,  // condition → TERNARY
    TERNARY_THEN,       // then-value → TERNARY
    TERNARY_ELSE,       // else-value → TERNARY

    // Lambda/method-ref
    LAMBDA_BODY,        // LAMBDA → root node of lambda body

    // Loop edges
    LOOP_INIT,          // init expression → LOOP(FOR)
    LOOP_UPDATE,        // update expression → LOOP(FOR)
    LOOP_ITERABLE,      // iterable expression → LOOP(FOREACH)

    // Call edges
    RECEIVER,           // receiver object → CALL

    // Assignment edges
    ASSIGN_TARGET,      // ASSIGN → target (LOCAL_DEF / FIELD_WRITE / ARRAY_ACCESS)
    ASSIGN_VALUE,       // value expression → ASSIGN

    // Statement edges
    THROW_VALUE,        // exception expression → THROW
    ASSERT_MESSAGE,     // message expression → ASSERT
    SYNC_LOCK,          // lock expression → SYNCHRONIZED
    CATCH_PARAM,        // BRANCH(TRY) → catch parameter node
    TRY_RESOURCE,       // BRANCH(TRY) → resource node (label = index)
    FINALLY_BODY,       // BRANCH(TRY) → first stmt of finally block
    YIELD_VALUE         // value → YIELD
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -10`
Expected: FAIL — compilation errors in MethodFlowBuilder.java (switch over FlowNodeKind in `prefixOf()` is no longer exhaustive since `TEMP_EXPR` was removed). This is expected; we fix it in Task 4.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/model/FlowNodeKind.java \
        src/main/java/com/github/sckwoky/typegraph/flow/model/FlowEdgeKind.java
git commit -m "feat: extend FlowNodeKind and FlowEdgeKind with expression-level types"
```

---

### Task 3: Add attributes map and stmtOrdinal to FlowNode

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/model/FlowNode.java`

- [ ] **Step 1: Add fields, constructor parameter, and accessors**

Add a `Map<String, String> attributes` field and `int stmtOrdinal` field to FlowNode. Add a new constructor that accepts them. Keep the old constructor for backward compatibility (defaults to empty map and -1 ordinal).

In `FlowNode.java`, add after line 29 (`private final String enclosingControlId;`):
```java
    private final Map<String, String> attributes;
    private final int stmtOrdinal;
```

Add the import `import java.util.Map;` after line 3.

Replace the constructor (lines 31-48) with two constructors:
```java
    public FlowNode(String id, FlowNodeKind kind, String label, int sourceLine,
                    String typeFqn, String variableName, int variableVersion,
                    MethodSignature callSignature, CallResolution callResolution,
                    FieldOrigin fieldOrigin, ControlSubtype controlSubtype,
                    String enclosingControlId,
                    Map<String, String> attributes, int stmtOrdinal) {
        this.id = Objects.requireNonNull(id);
        this.kind = Objects.requireNonNull(kind);
        this.label = label == null ? "" : label;
        this.sourceLine = sourceLine;
        this.typeFqn = typeFqn;
        this.variableName = variableName;
        this.variableVersion = variableVersion;
        this.callSignature = callSignature;
        this.callResolution = callResolution;
        this.fieldOrigin = fieldOrigin;
        this.controlSubtype = controlSubtype == null ? ControlSubtype.NONE : controlSubtype;
        this.enclosingControlId = enclosingControlId;
        this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        this.stmtOrdinal = stmtOrdinal;
    }

    /** Backward-compatible constructor — empty attributes, stmtOrdinal = -1. */
    public FlowNode(String id, FlowNodeKind kind, String label, int sourceLine,
                    String typeFqn, String variableName, int variableVersion,
                    MethodSignature callSignature, CallResolution callResolution,
                    FieldOrigin fieldOrigin, ControlSubtype controlSubtype,
                    String enclosingControlId) {
        this(id, kind, label, sourceLine, typeFqn, variableName, variableVersion,
                callSignature, callResolution, fieldOrigin, controlSubtype,
                enclosingControlId, null, -1);
    }
```

Add accessors after line 61 (`public String enclosingControlId()`):
```java
    public Map<String, String> attributes() { return attributes; }
    public int stmtOrdinal() { return stmtOrdinal; }

    /** Typed attribute access. Returns null if absent. */
    public String attr(String key) { return attributes.get(key); }
    public int attrInt(String key, int defaultValue) {
        var v = attributes.get(key);
        return v == null ? defaultValue : Integer.parseInt(v);
    }
    public boolean attrBool(String key) {
        return "true".equals(attributes.get(key));
    }
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -10`
Expected: FAIL due to `prefixOf()` in MethodFlowBuilder (TEMP_EXPR removed). This is expected.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/model/FlowNode.java
git commit -m "feat: add attributes map and stmtOrdinal to FlowNode"
```

---

### Task 4: Fix compilation — update prefixOf() and mkNode in MethodFlowBuilder

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java`

- [ ] **Step 1: Update prefixOf() to handle all new node kinds, remove TEMP_EXPR**

Replace `prefixOf()` method (lines 660-678) with:
```java
    private String prefixOf(FlowNodeKind kind) {
        return switch (kind) {
            case PARAM -> "param";
            case THIS_REF -> "this";
            case SUPER_REF -> "super";
            case FIELD_READ -> "fr";
            case FIELD_WRITE -> "fw";
            case LOCAL_DEF -> "def";
            case LOCAL_USE -> "use";
            case MERGE_VALUE -> "phi";
            case CALL -> "call";
            case CALL_RESULT -> "res";
            case RETURN -> "ret";
            case BRANCH -> "br";
            case MERGE -> "mrg";
            case LOOP -> "loop";
            case LITERAL -> "lit";
            case BINARY_OP -> "binop";
            case UNARY_OP -> "unop";
            case CAST -> "cast";
            case INSTANCEOF -> "iof";
            case ARRAY_CREATE -> "arrcr";
            case ARRAY_ACCESS -> "arrac";
            case TERNARY -> "tern";
            case OBJECT_CREATE -> "objcr";
            case LAMBDA -> "lam";
            case METHOD_REF -> "mref";
            case ASSIGN -> "asgn";
            case THROW -> "throw";
            case BREAK -> "brk";
            case CONTINUE -> "cont";
            case ASSERT -> "assert";
            case SYNCHRONIZED -> "sync";
            case SWITCH_CASE -> "case";
            case YIELD -> "yield";
        };
    }
```

- [ ] **Step 2: Temporarily alias mkTemp to use BINARY_OP instead of TEMP_EXPR**

Replace `mkTemp()` method (lines 637-644) with a temporary version that uses BINARY_OP as a catch-all (this will be properly refactored in Phase 2):
```java
    /** @deprecated Temporary bridge — will be replaced by specific expression node creation. */
    private FlowNode mkTemp(String label, String typeFqn, FlowNode... sources) {
        var temp = mkNode(FlowNodeKind.BINARY_OP, label, -1, typeFqn,
                null, -1, null, null, null, ControlSubtype.NONE);
        for (var s : sources) {
            if (s != null) graph.addEdge(s, temp, FlowEdgeKind.DATA_DEP);
        }
        return temp;
    }
```

- [ ] **Step 3: Verify compilation and tests pass**

Run: `./gradlew test 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all tests pass. (Some tests may need minor adjustments if they check for TEMP_EXPR nodes.)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java
git commit -m "fix: update prefixOf() for new node kinds, bridge TEMP_EXPR removal"
```

---

## Phase 2: Builder Refactor — Expression Sub-Graphs

### Task 5: Refactor analyzeExpr() — binary, unary, cast, instanceof, literal

This is the core refactoring: replace `mkTemp()` calls with structured expression nodes.

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java`

- [ ] **Step 1: Write failing test for expression sub-graph**

Add test to `src/test/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilderTest.java`:
```java
    @Test
    void binaryExpressionProducesSubGraph() {
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt");
        // "counter < retries" is a binary expression — should produce BINARY_OP node
        var binOps = graph.nodesOf(FlowNodeKind.BINARY_OP);
        assertThat(binOps).isNotEmpty();
        // Each BINARY_OP should have LEFT_OPERAND and RIGHT_OPERAND incoming edges
        for (var binOp : binOps) {
            var inEdges = graph.incomingEdgesOf(binOp);
            var edgeKinds = inEdges.stream()
                    .map(e -> e.kind())
                    .collect(Collectors.toSet());
            assertThat(edgeKinds).containsAnyOf(FlowEdgeKind.LEFT_OPERAND, FlowEdgeKind.RIGHT_OPERAND);
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*MethodFlowBuilderTest.binaryExpressionProducesSubGraph' 2>&1 | tail -10`
Expected: FAIL — BINARY_OP nodes exist (from mkTemp bridge) but don't have LEFT_OPERAND/RIGHT_OPERAND edges.

- [ ] **Step 3: Add helper for creating expression nodes with attributes**

Add to `MethodFlowBuilder.java` after `mkTemp()`:
```java
    private FlowNode mkExpr(FlowNodeKind kind, int line, String typeFqn,
                            Map<String, String> attributes) {
        var id = graph.nextId(prefixOf(kind));
        var node = new FlowNode(id, kind, "", line, typeFqn, null, -1,
                null, null, null, ControlSubtype.NONE, currentEnclosing(),
                attributes, stmtCounter++);
        graph.addNode(node);
        return node;
    }
```

Add the counter field near line 41 (after `private FlowNode thisRef;`):
```java
    private int stmtCounter = 0;
```

- [ ] **Step 4: Refactor analyzeBinary()**

Replace `analyzeBinary()` method (lines 585-589):
```java
    private FlowNode analyzeBinary(BinaryExpr be) {
        var l = analyzeExpr(be.getLeft());
        var r = analyzeExpr(be.getRight());
        var op = BinaryOperator.fromJavaParser(be.getOperator());
        var node = mkExpr(FlowNodeKind.BINARY_OP, lineOf(be), "boolean",
                Map.of("operator", op.name()));
        if (l != null) graph.addEdge(l, node, FlowEdgeKind.LEFT_OPERAND);
        if (r != null) graph.addEdge(r, node, FlowEdgeKind.RIGHT_OPERAND);
        return node;
    }
```

Add import at top of file:
```java
import com.github.sckwoky.typegraph.flow.model.BinaryOperator;
import com.github.sckwoky.typegraph.flow.model.UnaryOperator;
import com.github.sckwoky.typegraph.flow.model.AssignOperator;
import com.github.sckwoky.typegraph.flow.model.LiteralType;
import com.github.sckwoky.typegraph.flow.model.CallStyle;
```

- [ ] **Step 5: Refactor UnaryExpr handling**

Replace the UnaryExpr handler in `analyzeExpr()` (lines 382-385):
```java
        if (expr instanceof UnaryExpr ue) {
            var inner = analyzeExpr(ue.getExpression());
            var op = UnaryOperator.fromJavaParser(ue.getOperator());
            var node = mkExpr(FlowNodeKind.UNARY_OP, lineOf(ue),
                    inner != null ? inner.typeFqn() : null,
                    Map.of("operator", op.name()));
            if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.UNARY_OPERAND);
            return node;
        }
```

- [ ] **Step 6: Refactor CastExpr handling**

Replace the CastExpr handler (lines 386-389):
```java
        if (expr instanceof CastExpr ce) {
            var inner = analyzeExpr(ce.getExpression());
            var node = mkExpr(FlowNodeKind.CAST, lineOf(ce), ce.getType().asString(),
                    Map.of("targetType", ce.getType().asString()));
            if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.CAST_OPERAND);
            return node;
        }
```

- [ ] **Step 7: Refactor InstanceOfExpr handling**

Replace the InstanceOfExpr handler (lines 401-404):
```java
        if (expr instanceof InstanceOfExpr ioe) {
            var inner = analyzeExpr(ioe.getExpression());
            var attrs = new HashMap<String, String>();
            attrs.put("targetType", ioe.getType().asString());
            ioe.getPattern().ifPresent(p ->
                attrs.put("patternVar", p.getNameAsString()));
            var node = mkExpr(FlowNodeKind.INSTANCEOF, lineOf(ioe), "boolean", attrs);
            if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.INSTANCEOF_OPERAND);
            return node;
        }
```

Add `import java.util.HashMap;` if not already present.

- [ ] **Step 8: Refactor LiteralExpr handling**

Replace the LiteralExpr handler (lines 392-395):
```java
        if (expr instanceof LiteralExpr le) {
            LiteralType lt;
            String value;
            if (le instanceof IntegerLiteralExpr ile) { lt = LiteralType.INT; value = ile.getValue(); }
            else if (le instanceof LongLiteralExpr lle) { lt = LiteralType.LONG; value = lle.getValue(); }
            else if (le instanceof DoubleLiteralExpr dle) { lt = LiteralType.DOUBLE; value = dle.getValue(); }
            else if (le instanceof StringLiteralExpr sle) { lt = LiteralType.STRING; value = "\"" + sle.getValue() + "\""; }
            else if (le instanceof CharLiteralExpr cle) { lt = LiteralType.CHAR; value = "'" + cle.getValue() + "'"; }
            else if (le instanceof BooleanLiteralExpr ble) { lt = LiteralType.BOOLEAN; value = String.valueOf(ble.getValue()); }
            else if (le instanceof NullLiteralExpr) { lt = LiteralType.NULL; value = "null"; }
            else { lt = LiteralType.INT; value = le.toString(); }
            return mkNode(FlowNodeKind.LITERAL, value, lineOf(le),
                    null, null, -1, null, null, null, ControlSubtype.NONE);
        }
```

Add imports:
```java
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
```

- [ ] **Step 9: Run tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass, including the new `binaryExpressionProducesSubGraph`.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java \
        src/test/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilderTest.java
git commit -m "feat: expression sub-graphs for binary, unary, cast, instanceof, literal"
```

---

### Task 6: Refactor analyzeExpr() — array, ternary, lambda, method-ref, object creation

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java`

- [ ] **Step 1: Refactor ArrayAccessExpr**

Replace the ArrayAccessExpr handler (lines 396-400):
```java
        if (expr instanceof ArrayAccessExpr aae) {
            var arr = analyzeExpr(aae.getName());
            var idx = analyzeExpr(aae.getIndex());
            var node = mkExpr(FlowNodeKind.ARRAY_ACCESS, lineOf(aae), null, Map.of());
            if (arr != null) graph.addEdge(arr, node, FlowEdgeKind.ARRAY_REF);
            if (idx != null) graph.addEdge(idx, node, FlowEdgeKind.ARRAY_INDEX);
            return node;
        }
```

- [ ] **Step 2: Refactor ArrayCreationExpr**

Replace the ArrayCreationExpr handler (lines 410-414):
```java
        if (expr instanceof ArrayCreationExpr ace) {
            var node = mkExpr(FlowNodeKind.ARRAY_CREATE, lineOf(ace),
                    ace.getElementType().asString(),
                    Map.of("elementType", ace.getElementType().asString()));
            for (int i = 0; i < ace.getLevels().size(); i++) {
                var level = ace.getLevels().get(i);
                level.getDimension().ifPresent(dim -> {
                    var dimNode = analyzeExpr(dim);
                    if (dimNode != null) graph.addEdge(dimNode, node, FlowEdgeKind.ARRAY_DIM,
                            String.valueOf(i));
                });
            }
            return node;
        }
```

- [ ] **Step 3: Refactor ternary (ConditionalExpr)**

Replace `analyzeTernary()` method (lines 591-624):
```java
    private FlowNode analyzeTernary(ConditionalExpr ce) {
        var cond = analyzeExpr(ce.getCondition());
        var thenVal = analyzeExpr(ce.getThenExpr());
        var elseVal = analyzeExpr(ce.getElseExpr());
        var node = mkExpr(FlowNodeKind.TERNARY, lineOf(ce),
                thenVal != null ? thenVal.typeFqn() : null, Map.of());
        if (cond != null) graph.addEdge(cond, node, FlowEdgeKind.TERNARY_CONDITION);
        if (thenVal != null) graph.addEdge(thenVal, node, FlowEdgeKind.TERNARY_THEN);
        if (elseVal != null) graph.addEdge(elseVal, node, FlowEdgeKind.TERNARY_ELSE);
        return node;
    }
```

Note: This simplifies the ternary modeling — no more BRANCH/MERGE/MERGE_VALUE for ternary. The ternary becomes a pure expression node. This is correct for reconstruction purposes.

- [ ] **Step 4: Refactor LambdaExpr / MethodReferenceExpr**

Replace the lambda/method-ref handler (lines 405-409):
```java
        if (expr instanceof LambdaExpr le) {
            var paramNames = le.getParameters().stream()
                    .map(p -> p.getNameAsString()).toList();
            var paramTypes = le.getParameters().stream()
                    .map(p -> p.getType().asString()).toList();
            var attrs = new HashMap<String, String>();
            attrs.put("paramNames", String.join(",", paramNames));
            attrs.put("paramTypes", String.join(",", paramTypes));
            var node = mkExpr(FlowNodeKind.LAMBDA, lineOf(le), null, attrs);
            // Lambda body is opaque for now — documented limitation
            return node;
        }
        if (expr instanceof MethodReferenceExpr mre) {
            var attrs = new HashMap<String, String>();
            attrs.put("methodName", mre.getIdentifier());
            mre.getScope().ifPresent(scope -> {
                if (scope instanceof TypeExpr te) {
                    attrs.put("targetType", te.getType().asString());
                }
            });
            return mkExpr(FlowNodeKind.METHOD_REF, lineOf(mre), null, attrs);
        }
```

- [ ] **Step 5: Add RECEIVER edge to method calls**

In `analyzeMethodCall()`, replace the receiver edge (line 487):
```java
        if (receiver != null) graph.addEdge(receiver, call, FlowEdgeKind.RECEIVER);
```

And normalize ARG_PASS labels (line 490):
```java
                graph.addEdge(args.get(i), call, FlowEdgeKind.ARG_PASS, String.valueOf(i));
```

Similarly in `analyzeObjectCreation()` (line 524):
```java
            if (args.get(i) != null) graph.addEdge(args.get(i), call, FlowEdgeKind.ARG_PASS, String.valueOf(i));
```

- [ ] **Step 6: Add CALL.methodName attribute**

In `analyzeMethodCall()`, when creating the CALL node, add `methodName` to the label and use the new constructor to add attributes. Replace lines 484-485:
```java
        var callAttrs = new HashMap<String, String>();
        callAttrs.put("methodName", mce.getNameAsString());
        callAttrs.put("callStyle", receiver != null ? CallStyle.METHOD.name() : CallStyle.STATIC.name());
        var call = new FlowNode(graph.nextId(prefixOf(FlowNodeKind.CALL)),
                FlowNodeKind.CALL, mce.getNameAsString() + "()", lineOf(mce),
                returnType, null, -1, sig, res, null, ControlSubtype.NONE,
                currentEnclosing(), callAttrs, stmtCounter++);
        graph.addNode(call);
```

- [ ] **Step 7: Handle SuperExpr**

Replace the SuperExpr handler (line 376):
```java
        if (expr instanceof SuperExpr) return ensureSuperRef();
```

Add the `ensureSuperRef()` method:
```java
    private FlowNode superRef;

    private FlowNode ensureSuperRef() {
        if (superRef == null) {
            superRef = mkNode(FlowNodeKind.SUPER_REF, "super", -1, declaringTypeFqn,
                    null, -1, null, null, null, ControlSubtype.NONE);
        }
        return superRef;
    }
```

- [ ] **Step 8: Run tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java
git commit -m "feat: expression sub-graphs for array, ternary, lambda, method-ref, super"
```

---

### Task 7: Add CONDITION edges to BRANCH/LOOP, fix control node sourceLine

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java`

- [ ] **Step 1: Write failing test**

Add to `MethodFlowBuilderTest.java`:
```java
    @Test
    void branchNodesHaveConditionEdge() {
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt");
        for (var branch : graph.branchNodes()) {
            if (branch.controlSubtype() == ControlSubtype.IF) {
                var condEdges = graph.incomingEdgesOf(branch).stream()
                        .filter(e -> e.kind() == FlowEdgeKind.CONDITION)
                        .toList();
                assertThat(condEdges).as("IF branch should have CONDITION edge: " + branch).isNotEmpty();
            }
        }
    }
```

- [ ] **Step 2: Run test — expect failure**

Run: `./gradlew test --tests '*MethodFlowBuilderTest.branchNodesHaveConditionEdge' 2>&1 | tail -10`
Expected: FAIL — currently conditions connect via DATA_DEP with "cond" label.

- [ ] **Step 3: Fix processIf() — use CONDITION edge**

In `processIf()` (line 147), replace:
```java
        if (condVal != null) graph.addEdge(condVal, branch, FlowEdgeKind.DATA_DEP, "cond");
```
with:
```java
        if (condVal != null) graph.addEdge(condVal, branch, FlowEdgeKind.CONDITION);
```

- [ ] **Step 4: Fix processWhile(), processDo(), processFor() — CONDITION edges**

In `processWhile()` (line 181):
```java
        if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.CONDITION);
```

In `processDo()` (line 206):
```java
        if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.CONDITION);
```

In `processFor()` — replace the compare handler (lines 216-219):
```java
        fs.getCompare().ifPresent(c -> {
            var cond = analyzeExpr(c);
            if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.CONDITION);
        });
```

Also add LOOP_INIT and LOOP_UPDATE edges. Refactor `processFor()` to capture init/update nodes:

Replace lines 213-232 of `processFor()`:
```java
    private void processFor(ForStmt fs) {
        scope.pushFrame();
        var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.FOR, "for", lineOf(fs));

        // Init expressions → LOOP_INIT
        for (var init : fs.getInitialization()) {
            var initNode = analyzeExpr(init);
            if (initNode != null) graph.addEdge(initNode, loop, FlowEdgeKind.LOOP_INIT);
        }

        // Condition → CONDITION
        fs.getCompare().ifPresent(c -> {
            var cond = analyzeExpr(c);
            if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.CONDITION);
        });

        var before = scope.snapshot();
        enclosingControlStack.push(loop.id());
        scope.pushFrame();
        processStmt(fs.getBody());

        // Update expressions → LOOP_UPDATE
        for (var upd : fs.getUpdate()) {
            var updNode = analyzeExpr(upd);
            if (updNode != null) graph.addEdge(updNode, loop, FlowEdgeKind.LOOP_UPDATE);
        }

        var after = scope.snapshot();
        scope.popFrame();
        enclosingControlStack.pop();
        scope.restoreFromSnapshot(before);
        mergeBranches(before, List.of(before, after), loop);

        scope.popFrame();
    }
```

For `processForEach()` (line 238):
```java
        if (iter != null) graph.addEdge(iter, loop, FlowEdgeKind.LOOP_ITERABLE);
```
(Replace the existing `FlowEdgeKind.DATA_DEP, "iterable"` edge.)

- [ ] **Step 5: Fix processSwitch() — use CONDITION edge**

In `processSwitch()` (line 317):
```java
        if (sel != null) graph.addEdge(sel, branch, FlowEdgeKind.CONDITION);
```

- [ ] **Step 6: Fix mkControl() — use AST line instead of -1**

Replace `mkControl()` (line 646-648) — it needs to accept a source line:
```java
    private FlowNode mkControl(FlowNodeKind kind, ControlSubtype subtype, String label, int line) {
        return mkNode(kind, label, line, null, null, -1, null, null, null, subtype);
    }

    /** Overload for backward compat — will migrate callers incrementally. */
    private FlowNode mkControl(FlowNodeKind kind, ControlSubtype subtype, String label) {
        return mkControl(kind, subtype, label, -1);
    }
```

Update all `mkControl` call sites in `processIf`, `processWhile`, `processDo`, `processFor`, `processForEach`, `processTry`, `processSwitch` to pass `lineOf(stmt)` instead of relying on the -1 default. Example for `processIf()`:
```java
        var branch = mkControl(FlowNodeKind.BRANCH, ControlSubtype.IF, "if", lineOf(is));
```

- [ ] **Step 7: Run tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java \
        src/test/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilderTest.java
git commit -m "feat: CONDITION edges for branches/loops, fix control node sourceLine"
```

---

### Task 8: Add statement-level nodes (throw, break, continue, assert, assign)

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java`

- [ ] **Step 1: Add THROW node creation**

In `processStmt()`, replace the ThrowStmt handler (lines 119-120):
```java
        } else if (s instanceof ThrowStmt ths) {
            var exprNode = analyzeExpr(ths.getExpression());
            var throwNode = mkExpr(FlowNodeKind.THROW, lineOf(ths), null, Map.of());
            if (exprNode != null) graph.addEdge(exprNode, throwNode, FlowEdgeKind.THROW_VALUE);
```

- [ ] **Step 2: Add BREAK and CONTINUE nodes**

In `processStmt()`, replace the comment about ignored statements (line 130) — add before it:
```java
        } else if (s instanceof BreakStmt bs) {
            var attrs = new HashMap<String, String>();
            bs.getLabel().ifPresent(l -> attrs.put("targetLabel", l.getIdentifier()));
            mkExpr(FlowNodeKind.BREAK, lineOf(bs), null, attrs);
        } else if (s instanceof ContinueStmt cs) {
            var attrs = new HashMap<String, String>();
            cs.getLabel().ifPresent(l -> attrs.put("targetLabel", l.getIdentifier()));
            mkExpr(FlowNodeKind.CONTINUE, lineOf(cs), null, attrs);
```

- [ ] **Step 3: Add ASSERT node**

Replace the AssertStmt handler (lines 126-129):
```java
        } else if (s instanceof AssertStmt as) {
            var checkNode = analyzeExpr(as.getCheck());
            var assertNode = mkExpr(FlowNodeKind.ASSERT, lineOf(as), null, Map.of());
            if (checkNode != null) graph.addEdge(checkNode, assertNode, FlowEdgeKind.CONDITION);
            as.getMessage().ifPresent(msg -> {
                var msgNode = analyzeExpr(msg);
                if (msgNode != null) graph.addEdge(msgNode, assertNode, FlowEdgeKind.ASSERT_MESSAGE);
            });
```

- [ ] **Step 4: Add SYNCHRONIZED node**

Replace the SynchronizedStmt handler in `processStmt()` (lines 121-123):
```java
        } else if (s instanceof SynchronizedStmt ss) {
            var lockNode = analyzeExpr(ss.getExpression());
            var syncNode = mkExpr(FlowNodeKind.SYNCHRONIZED, lineOf(ss), null, Map.of());
            if (lockNode != null) graph.addEdge(lockNode, syncNode, FlowEdgeKind.SYNC_LOCK);
            enclosingControlStack.push(syncNode.id());
            processBlock(ss.getBody());
            enclosingControlStack.pop();
```

- [ ] **Step 5: Fix analyzeAssign — use ASSIGN node for reassignment**

Replace the name-target branch of `analyzeAssign()` (lines 535-543):
```java
        if (target instanceof NameExpr ne) {
            String name = ne.getNameAsString();
            if (scope.currentDef(name) != null || !fields.contains(name)) {
                var op = AssignOperator.fromJavaParser(ae.getOperator());
                if (op == AssignOperator.ASSIGN) {
                    // Simple reassignment: create a new LOCAL_DEF version
                    var def = mkNode(FlowNodeKind.LOCAL_DEF, name + ":=", lineOf(ae),
                            rhs != null ? rhs.typeFqn() : null,
                            name, scope.nextVersion(name), null, null, null, ControlSubtype.NONE);
                    if (rhs != null) graph.addEdge(rhs, def, FlowEdgeKind.DATA_DEP, "value");
                    scope.update(name, def);
                    return def;
                } else {
                    // Compound assignment (+=, -=, etc.): create ASSIGN node
                    var assignNode = mkExpr(FlowNodeKind.ASSIGN, lineOf(ae), null,
                            Map.of("assignOp", op.name()));
                    var currentDef = scope.currentDef(name);
                    if (currentDef != null) graph.addEdge(currentDef, assignNode, FlowEdgeKind.ASSIGN_VALUE);
                    if (rhs != null) graph.addEdge(rhs, assignNode, FlowEdgeKind.ASSIGN_VALUE);
                    var newDef = mkNode(FlowNodeKind.LOCAL_DEF, name + ":=", lineOf(ae),
                            rhs != null ? rhs.typeFqn() : null,
                            name, scope.nextVersion(name), null, null, null, ControlSubtype.NONE);
                    graph.addEdge(assignNode, newDef, FlowEdgeKind.ASSIGN_TARGET);
                    scope.update(name, newDef);
                    return newDef;
                }
            } else {
```
(Rest of the field-write logic stays the same.)

- [ ] **Step 6: Run tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java
git commit -m "feat: add THROW, BREAK, CONTINUE, ASSERT, SYNCHRONIZED, ASSIGN nodes to builder"
```

---

### Task 9: Update BackwardSlicer and FlowChainExtractor for new node/edge kinds

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/BackwardSlicer.java`
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/FlowChainExtractor.java`

- [ ] **Step 1: Update BackwardSlicer DATA_KINDS**

In `BackwardSlicer.java`, add new edge kinds to `DATA_KINDS` set (after line 29):
```java
    private static final EnumSet<FlowEdgeKind> DATA_KINDS = EnumSet.of(
            FlowEdgeKind.DATA_DEP,
            FlowEdgeKind.ARG_PASS,
            FlowEdgeKind.CALL_RESULT_OF,
            FlowEdgeKind.RETURN_DEP,
            FlowEdgeKind.DEF_USE,
            FlowEdgeKind.PHI_INPUT,
            // Expression edges
            FlowEdgeKind.LEFT_OPERAND,
            FlowEdgeKind.RIGHT_OPERAND,
            FlowEdgeKind.UNARY_OPERAND,
            FlowEdgeKind.CAST_OPERAND,
            FlowEdgeKind.INSTANCEOF_OPERAND,
            FlowEdgeKind.ARRAY_REF,
            FlowEdgeKind.ARRAY_INDEX,
            FlowEdgeKind.ARRAY_DIM,
            FlowEdgeKind.TERNARY_CONDITION,
            FlowEdgeKind.TERNARY_THEN,
            FlowEdgeKind.TERNARY_ELSE,
            FlowEdgeKind.RECEIVER,
            FlowEdgeKind.ASSIGN_VALUE,
            FlowEdgeKind.THROW_VALUE,
            FlowEdgeKind.YIELD_VALUE,
            FlowEdgeKind.CONDITION
    );
```

- [ ] **Step 2: Update FlowChainExtractor technical node set**

In `FlowChainExtractor.java`, find the set of technical/skip nodes (used in `dfs()` method, around line 85-90 where it skips certain node kinds). Add the new expression node kinds to the skip set:
```java
    private static final EnumSet<FlowNodeKind> TECHNICAL_KINDS = EnumSet.of(
            FlowNodeKind.LOCAL_DEF,
            FlowNodeKind.LOCAL_USE,
            FlowNodeKind.MERGE_VALUE,
            FlowNodeKind.MERGE,
            FlowNodeKind.BRANCH,
            FlowNodeKind.LOOP,
            // New expression nodes — technical for chain extraction
            FlowNodeKind.BINARY_OP,
            FlowNodeKind.UNARY_OP,
            FlowNodeKind.CAST,
            FlowNodeKind.INSTANCEOF,
            FlowNodeKind.ARRAY_ACCESS,
            FlowNodeKind.ARRAY_CREATE,
            FlowNodeKind.TERNARY,
            FlowNodeKind.ASSIGN,
            FlowNodeKind.LITERAL
    );
```

(Verify how the existing code checks for technical nodes — it may use inline checks rather than a set. Adapt accordingly.)

- [ ] **Step 3: Run tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/BackwardSlicer.java \
        src/main/java/com/github/sckwoky/typegraph/flow/FlowChainExtractor.java
git commit -m "fix: update BackwardSlicer and FlowChainExtractor for new expression nodes"
```

---

## Phase 3: Code Reconstructor

### Task 10: Create test fixture and FlowCodeReconstructor skeleton

**Files:**
- Create: `src/test/resources/fixtures/com/example/ReconstructionFixture.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/FlowCodeReconstructor.java`
- Create: `src/test/java/com/github/sckwoky/typegraph/flow/FlowCodeReconstructorTest.java`

- [ ] **Step 1: Create test fixture with representative Java constructs**

```java
package com.example;

public class ReconstructionFixture {

    private int counter;

    public int simpleReturn(int x) {
        return x + 1;
    }

    public String ifElse(int x) {
        String result;
        if (x > 0) {
            result = "positive";
        } else {
            result = "non-positive";
        }
        return result;
    }

    public int whileLoop(int n) {
        int sum = 0;
        int i = 0;
        while (i < n) {
            sum = sum + i;
            i = i + 1;
        }
        return sum;
    }

    public int forLoop(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum = sum + i;
        }
        return sum;
    }

    public void methodCall(Owner owner) {
        owner.adoptDog("Rex", 3);
    }

    public String ternary(int x) {
        return x > 0 ? "yes" : "no";
    }

    public String nullCheck(Object obj) {
        if (obj == null) {
            return "null";
        }
        return obj.toString();
    }

    public void voidReturn() {
        return;
    }
}
```

- [ ] **Step 2: Create FlowCodeReconstructor skeleton**

```java
package com.github.sckwoky.typegraph.flow;

import com.github.sckwoky.typegraph.flow.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Reconstructs Java source code from a {@link MethodFlowGraph}.
 * <p>
 * Two-pass algorithm:
 * <ol>
 *   <li>Pass 1: Restore block tree from flat graph using enclosingControlId and control edges.</li>
 *   <li>Pass 2: Generate code by walking expression sub-trees for each statement node.</li>
 * </ol>
 */
public class FlowCodeReconstructor {

    private final MethodFlowGraph graph;
    private final StringBuilder sb = new StringBuilder();
    private int indent = 1;

    public FlowCodeReconstructor(MethodFlowGraph graph) {
        this.graph = graph;
    }

    /**
     * Reconstruct the method source code.
     * @return Java method source as a string
     */
    public String reconstruct() {
        var sig = graph.methodSignature();
        sb.append("    "); // class-level indent
        sb.append(sig.returnType()).append(" ").append(sig.methodName()).append("(");
        var params = graph.paramNodes();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            var p = params.get(i);
            sb.append(p.typeFqn()).append(" ").append(p.variableName());
        }
        sb.append(") {\n");

        var stmtRoots = collectStatementRoots();
        var topLevel = stmtRoots.stream()
                .filter(n -> n.enclosingControlId() == null)
                .sorted(Comparator.comparingInt(FlowNode::stmtOrdinal)
                        .thenComparingInt(FlowNode::sourceLine))
                .toList();

        for (var stmt : topLevel) {
            renderStatement(stmt);
        }

        sb.append("    }\n");
        return sb.toString();
    }

    // ─── Pass 1: Statement root collection ─────────────────────────────

    private static final EnumSet<FlowNodeKind> STMT_ROOT_KINDS = EnumSet.of(
            FlowNodeKind.LOCAL_DEF, FlowNodeKind.ASSIGN, FlowNodeKind.CALL,
            FlowNodeKind.RETURN, FlowNodeKind.BRANCH, FlowNodeKind.LOOP,
            FlowNodeKind.FIELD_WRITE, FlowNodeKind.THROW, FlowNodeKind.BREAK,
            FlowNodeKind.CONTINUE, FlowNodeKind.ASSERT, FlowNodeKind.SYNCHRONIZED,
            FlowNodeKind.YIELD
    );

    private List<FlowNode> collectStatementRoots() {
        // Collect nodes owned by ASSIGN_TARGET — exclude them as independent stmts
        var assignTargets = new HashSet<FlowNode>();
        for (var edge : graph.edges()) {
            if (edge.kind() == FlowEdgeKind.ASSIGN_TARGET) {
                assignTargets.add(graph.getEdgeTarget(edge));
            }
        }
        return graph.nodes().stream()
                .filter(n -> STMT_ROOT_KINDS.contains(n.kind()))
                .filter(n -> !assignTargets.contains(n))
                // Exclude CALL nodes that are receivers (part of chain)
                .filter(n -> n.kind() != FlowNodeKind.CALL ||
                        graph.outgoingEdgesOf(n).stream().noneMatch(e -> e.kind() == FlowEdgeKind.CALL_RESULT_OF))
                .toList();
    }

    // ─── Pass 2: Code generation ───────────────────────────────────────

    private void renderStatement(FlowNode node) {
        line(renderNode(node) + ";");
    }

    private String renderNode(FlowNode node) {
        return switch (node.kind()) {
            case LITERAL -> node.label();
            case LOCAL_USE -> node.variableName();
            case LOCAL_DEF -> renderLocalDef(node);
            case BINARY_OP -> renderBinaryOp(node);
            case UNARY_OP -> renderUnaryOp(node);
            case CALL -> renderCall(node);
            case CALL_RESULT -> renderNode(findCallForResult(node));
            case RETURN -> renderReturn(node);
            case FIELD_READ -> renderFieldRead(node);
            case FIELD_WRITE -> renderFieldWrite(node);
            case CAST -> renderCast(node);
            case INSTANCEOF -> renderInstanceOf(node);
            case TERNARY -> renderTernary(node);
            case ARRAY_ACCESS -> renderArrayAccess(node);
            case THIS_REF -> "this";
            case SUPER_REF -> "super";
            case PARAM -> node.variableName();
            case MERGE_VALUE -> renderMergeValue(node);
            case THROW -> renderThrow(node);
            case BREAK -> renderBreak(node);
            case CONTINUE -> renderContinue(node);
            case ASSERT -> renderAssert(node);
            default -> "/* TODO: " + node.kind() + " */";
        };
    }

    // ─── Expression renderers ──────────────────────────────────────────

    private String renderBinaryOp(FlowNode node) {
        var op = node.attr("operator");
        var symbol = BinaryOperator.valueOf(op).symbol();
        var left = findEdgeSource(node, FlowEdgeKind.LEFT_OPERAND);
        var right = findEdgeSource(node, FlowEdgeKind.RIGHT_OPERAND);
        return renderNode(left) + " " + symbol + " " + renderNode(right);
    }

    private String renderUnaryOp(FlowNode node) {
        var op = UnaryOperator.valueOf(node.attr("operator"));
        var operand = findEdgeSource(node, FlowEdgeKind.UNARY_OPERAND);
        return op.prefix()
                ? op.symbol() + renderNode(operand)
                : renderNode(operand) + op.symbol();
    }

    private String renderLocalDef(FlowNode node) {
        var value = findEdgeSource(node, FlowEdgeKind.DATA_DEP);
        if (value != null) {
            return node.typeFqn() + " " + node.variableName() + " = " + renderNode(value);
        }
        return node.typeFqn() + " " + node.variableName();
    }

    private String renderCall(FlowNode node) {
        var methodName = node.attr("methodName");
        if (methodName == null) methodName = node.label().replace("()", "");
        var receiver = findEdgeSource(node, FlowEdgeKind.RECEIVER);
        var args = findArgSources(node);
        var argStr = args.stream().map(this::renderNode).collect(Collectors.joining(", "));
        if (receiver != null) {
            return renderNode(receiver) + "." + methodName + "(" + argStr + ")";
        }
        return methodName + "(" + argStr + ")";
    }

    private String renderReturn(FlowNode node) {
        var value = findEdgeSource(node, FlowEdgeKind.RETURN_DEP);
        return value != null ? "return " + renderNode(value) : "return";
    }

    private String renderFieldRead(FlowNode node) {
        var receiver = findEdgeSource(node, FlowEdgeKind.DATA_DEP);
        var name = node.variableName() != null ? node.variableName() : node.label();
        if (receiver != null && receiver.kind() != FlowNodeKind.THIS_REF) {
            return renderNode(receiver) + "." + name;
        }
        return "this." + name;
    }

    private String renderFieldWrite(FlowNode node) {
        var value = findEdgeSourceByLabel(node, FlowEdgeKind.DATA_DEP, "value");
        var name = node.variableName() != null ? node.variableName() : node.label();
        return "this." + name + " = " + (value != null ? renderNode(value) : "???");
    }

    private String renderCast(FlowNode node) {
        var operand = findEdgeSource(node, FlowEdgeKind.CAST_OPERAND);
        return "(" + node.attr("targetType") + ") " + renderNode(operand);
    }

    private String renderInstanceOf(FlowNode node) {
        var operand = findEdgeSource(node, FlowEdgeKind.INSTANCEOF_OPERAND);
        var pat = node.attr("patternVar");
        var base = renderNode(operand) + " instanceof " + node.attr("targetType");
        return pat != null ? base + " " + pat : base;
    }

    private String renderTernary(FlowNode node) {
        var cond = findEdgeSource(node, FlowEdgeKind.TERNARY_CONDITION);
        var then = findEdgeSource(node, FlowEdgeKind.TERNARY_THEN);
        var els = findEdgeSource(node, FlowEdgeKind.TERNARY_ELSE);
        return renderNode(cond) + " ? " + renderNode(then) + " : " + renderNode(els);
    }

    private String renderArrayAccess(FlowNode node) {
        var arr = findEdgeSource(node, FlowEdgeKind.ARRAY_REF);
        var idx = findEdgeSource(node, FlowEdgeKind.ARRAY_INDEX);
        return renderNode(arr) + "[" + renderNode(idx) + "]";
    }

    private String renderMergeValue(FlowNode node) {
        // Pick first PHI_INPUT
        var input = findEdgeSource(node, FlowEdgeKind.PHI_INPUT);
        return input != null ? renderNode(input) : node.variableName();
    }

    private String renderThrow(FlowNode node) {
        var value = findEdgeSource(node, FlowEdgeKind.THROW_VALUE);
        return "throw " + (value != null ? renderNode(value) : "???");
    }

    private String renderBreak(FlowNode node) {
        var label = node.attr("targetLabel");
        return label != null ? "break " + label : "break";
    }

    private String renderContinue(FlowNode node) {
        var label = node.attr("targetLabel");
        return label != null ? "continue " + label : "continue";
    }

    private String renderAssert(FlowNode node) {
        var cond = findEdgeSource(node, FlowEdgeKind.CONDITION);
        var msg = findEdgeSource(node, FlowEdgeKind.ASSERT_MESSAGE);
        var base = "assert " + renderNode(cond);
        return msg != null ? base + " : " + renderNode(msg) : base;
    }

    // ─── Graph navigation helpers ──────────────────────────────────────

    private FlowNode findEdgeSource(FlowNode target, FlowEdgeKind kind) {
        return graph.incomingEdgesOf(target).stream()
                .filter(e -> e.kind() == kind)
                .map(graph::getEdgeSource)
                .findFirst().orElse(null);
    }

    private FlowNode findEdgeSourceByLabel(FlowNode target, FlowEdgeKind kind, String label) {
        return graph.incomingEdgesOf(target).stream()
                .filter(e -> e.kind() == kind && label.equals(e.label()))
                .map(graph::getEdgeSource)
                .findFirst().orElse(null);
    }

    private FlowNode findCallForResult(FlowNode resultNode) {
        return graph.incomingEdgesOf(resultNode).stream()
                .filter(e -> e.kind() == FlowEdgeKind.CALL_RESULT_OF)
                .map(graph::getEdgeSource)
                .findFirst().orElse(resultNode);
    }

    private List<FlowNode> findArgSources(FlowNode callNode) {
        return graph.incomingEdgesOf(callNode).stream()
                .filter(e -> e.kind() == FlowEdgeKind.ARG_PASS)
                .sorted(Comparator.comparing(FlowEdge::label))
                .map(graph::getEdgeSource)
                .toList();
    }

    // ─── Output helpers ────────────────────────────────────────────────

    private void line(String text) {
        sb.append("        ".repeat(indent)).append(text).append("\n");
    }
}
```

- [ ] **Step 3: Create basic test**

```java
package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlowCodeReconstructorTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");
    private static List<ProjectFlowGraphs.Entry> entries;

    @BeforeAll
    static void setUp() {
        var typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(FIXTURES));
        var config = new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver));
        StaticJavaParser.setConfiguration(config);
        entries = new ProjectFlowGraphs().buildAll(List.of(FIXTURES), t -> true);
    }

    private MethodFlowGraph findMethod(String declaring, String name) {
        return entries.stream()
                .filter(e -> e.declaringType().equals(declaring) && e.methodName().equals(name))
                .findFirst().orElseThrow().graph();
    }

    @Test
    void simpleReturnReconstructsParseably() {
        var graph = findMethod("com.example.ReconstructionFixture", "simpleReturn");
        var reconstructor = new FlowCodeReconstructor(graph);
        var code = reconstructor.reconstruct();
        assertThat(code).contains("return");
        assertThat(code).contains("+");
        // Should be parseable by JavaParser
        var result = StaticJavaParser.parse("class __V { " + code + " }");
        assertThat(result.getType(0).getMethods()).hasSize(1);
    }

    @Test
    void methodCallReconstructsWithArguments() {
        var graph = findMethod("com.example.ReconstructionFixture", "methodCall");
        var reconstructor = new FlowCodeReconstructor(graph);
        var code = reconstructor.reconstruct();
        assertThat(code).contains("adoptDog");
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*FlowCodeReconstructorTest*' 2>&1 | tail -20`
Expected: Tests pass (basic reconstruction works).

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/fixtures/com/example/ReconstructionFixture.java \
        src/main/java/com/github/sckwoky/typegraph/flow/FlowCodeReconstructor.java \
        src/test/java/com/github/sckwoky/typegraph/flow/FlowCodeReconstructorTest.java
git commit -m "feat: FlowCodeReconstructor skeleton with expression rendering"
```

---

### Task 11: Add control structure rendering to FlowCodeReconstructor

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/FlowCodeReconstructor.java`
- Modify: `src/test/java/com/github/sckwoky/typegraph/flow/FlowCodeReconstructorTest.java`

- [ ] **Step 1: Add failing test for if/else reconstruction**

Add to `FlowCodeReconstructorTest.java`:
```java
    @Test
    void ifElseReconstructsWithCondition() {
        var graph = findMethod("com.example.ReconstructionFixture", "ifElse");
        var reconstructor = new FlowCodeReconstructor(graph);
        var code = reconstructor.reconstruct();
        assertThat(code).contains("if");
        assertThat(code).contains("else");
        assertThat(code).contains(">");
        // Parseable
        var result = StaticJavaParser.parse("class __V { " + code + " }");
        assertThat(result.getType(0).getMethods()).hasSize(1);
    }
```

- [ ] **Step 2: Implement BRANCH/LOOP rendering in renderStatement()**

In `FlowCodeReconstructor.java`, override `renderStatement()` to handle control structures:
```java
    private void renderStatement(FlowNode node) {
        switch (node.kind()) {
            case BRANCH -> renderBranch(node);
            case LOOP -> renderLoop(node);
            case SYNCHRONIZED -> renderSynchronized(node);
            default -> line(renderNode(node) + ";");
        }
    }

    private void renderBranch(FlowNode node) {
        switch (node.controlSubtype()) {
            case IF -> renderIf(node);
            case TRY -> renderTry(node);
            case SWITCH -> renderSwitch(node);
            default -> line("/* unhandled branch: " + node.controlSubtype() + " */");
        }
    }

    private void renderIf(FlowNode node) {
        var cond = findEdgeSource(node, FlowEdgeKind.CONDITION);
        line("if (" + (cond != null ? renderNode(cond) : "???") + ") {");
        indent++;
        renderChildBlock(node, null); // then-block: children with this enclosingControlId
        indent--;
        // TODO: else-block detection via ELSE_BRANCH edges or separate else enclosingControlId
        line("}");
    }

    private void renderLoop(FlowNode node) {
        var cond = findEdgeSource(node, FlowEdgeKind.CONDITION);
        switch (node.controlSubtype()) {
            case WHILE -> {
                line("while (" + (cond != null ? renderNode(cond) : "???") + ") {");
                indent++;
                renderChildBlock(node, null);
                indent--;
                line("}");
            }
            case FOR -> {
                line("for (/* init */; " + (cond != null ? renderNode(cond) : "") + "; /* update */) {");
                indent++;
                renderChildBlock(node, null);
                indent--;
                line("}");
            }
            case FOREACH -> {
                var iterable = findEdgeSource(node, FlowEdgeKind.LOOP_ITERABLE);
                line("for (var __elem : " + (iterable != null ? renderNode(iterable) : "???") + ") {");
                indent++;
                renderChildBlock(node, null);
                indent--;
                line("}");
            }
            case DO -> {
                line("do {");
                indent++;
                renderChildBlock(node, null);
                indent--;
                line("} while (" + (cond != null ? renderNode(cond) : "???") + ");");
            }
            default -> line("/* unhandled loop: " + node.controlSubtype() + " */");
        }
    }

    private void renderTry(FlowNode node) {
        line("try {");
        indent++;
        renderChildBlock(node, null);
        indent--;
        // Catch blocks
        for (var edge : graph.outgoingEdgesOf(node)) {
            if (edge.kind() == FlowEdgeKind.CONTROL_DEP) {
                var child = graph.getEdgeTarget(edge);
                if (child.kind() == FlowNodeKind.BRANCH && child.controlSubtype() == ControlSubtype.CATCH) {
                    line("} catch (Exception e) {");
                    indent++;
                    renderChildBlock(child, null);
                    indent--;
                }
            }
        }
        line("}");
    }

    private void renderSwitch(FlowNode node) {
        var selector = findEdgeSource(node, FlowEdgeKind.CONDITION);
        line("switch (" + (selector != null ? renderNode(selector) : "???") + ") {");
        indent++;
        renderChildBlock(node, null);
        indent--;
        line("}");
    }

    private void renderSynchronized(FlowNode node) {
        var lock = findEdgeSource(node, FlowEdgeKind.SYNC_LOCK);
        line("synchronized (" + (lock != null ? renderNode(lock) : "???") + ") {");
        indent++;
        renderChildBlock(node, null);
        indent--;
        line("}");
    }

    private void renderChildBlock(FlowNode controlNode, String blockFilter) {
        var children = collectStatementRoots().stream()
                .filter(n -> controlNode.id().equals(n.enclosingControlId()))
                .sorted(Comparator.comparingInt(FlowNode::stmtOrdinal)
                        .thenComparingInt(FlowNode::sourceLine))
                .toList();
        for (var child : children) {
            renderStatement(child);
        }
    }
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests '*FlowCodeReconstructorTest*' 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/FlowCodeReconstructor.java \
        src/test/java/com/github/sckwoky/typegraph/flow/FlowCodeReconstructorTest.java
git commit -m "feat: control structure rendering in FlowCodeReconstructor"
```

---

## Phase 4: Round-Trip Verifier

### Task 12: Create FlowRoundTripVerifier

**Files:**
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/FlowRoundTripVerifier.java`
- Create: `src/test/java/com/github/sckwoky/typegraph/flow/FlowRoundTripVerifierTest.java`

- [ ] **Step 1: Create VerificationResult record and verifier class**

```java
package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.nio.file.Path;
import java.util.*;

/**
 * Round-trip verification: source → graph → reconstructed code → compare.
 */
public class FlowRoundTripVerifier {

    public record VerificationResult(
            String methodSignature,
            boolean parseable,
            List<String> diffs,
            double similarityScore,
            String originalCode,
            String reconstructedCode
    ) {}

    public record ProjectReport(
            int totalMethods,
            int parseable,
            double averageScore,
            List<VerificationResult> worstMethods
    ) {}

    /**
     * Verify a single method: build graph, reconstruct, compare.
     */
    public VerificationResult verify(ProjectFlowGraphs.Entry entry, String originalMethodSource) {
        var graph = entry.graph();
        var reconstructor = new FlowCodeReconstructor(graph);
        var reconstructed = reconstructor.reconstruct();

        // Level 1: parseability
        boolean parseable;
        try {
            StaticJavaParser.parse("class __V { " + reconstructed + " }");
            parseable = true;
        } catch (ParseProblemException e) {
            parseable = false;
        }

        // Level 2-3: structural comparison
        var diffs = new ArrayList<String>();
        double score = 0.0;

        if (parseable) {
            score = computeSimilarity(originalMethodSource, reconstructed, diffs);
        }

        return new VerificationResult(
                entry.graph().methodSignature().toString(),
                parseable, diffs, score,
                originalMethodSource, reconstructed
        );
    }

    /**
     * Verify all methods in a project.
     */
    public ProjectReport verifyProject(List<Path> sourceRoots) {
        var entries = new ProjectFlowGraphs().buildAll(sourceRoots, t -> true);
        var results = new ArrayList<VerificationResult>();

        for (var entry : entries) {
            // Extract original method source from the AST
            // (We use the method signature to locate it, but for now use a placeholder)
            var result = verify(entry, "/* original not available */");
            results.add(result);
        }

        int parseable = (int) results.stream().filter(VerificationResult::parseable).count();
        double avgScore = results.stream().mapToDouble(VerificationResult::similarityScore).average().orElse(0);

        var worst = results.stream()
                .sorted(Comparator.comparingDouble(VerificationResult::similarityScore))
                .limit(10)
                .toList();

        return new ProjectReport(results.size(), parseable, avgScore, worst);
    }

    private double computeSimilarity(String original, String reconstructed, List<String> diffs) {
        // Simple heuristic: check structural markers
        double score = 0.0;
        double total = 0.0;

        // Control structures (40%)
        total += 40;
        score += 40.0 * structureScore(original, reconstructed,
                List.of("if", "else", "for", "while", "do", "switch", "try", "catch", "finally"));

        // Method calls (30%)
        total += 30;
        score += 30.0 * callScore(original, reconstructed);

        // Expressions (20%)
        total += 20;
        score += 20.0 * expressionScore(original, reconstructed);

        // Declarations (10%)
        total += 10;
        score += 10.0 * declarationScore(original, reconstructed);

        return score / total;
    }

    private double structureScore(String original, String reconstructed, List<String> keywords) {
        int matches = 0;
        int total = 0;
        for (var kw : keywords) {
            int origCount = countOccurrences(original, kw);
            int reconCount = countOccurrences(reconstructed, kw);
            if (origCount > 0 || reconCount > 0) {
                total++;
                if (origCount == reconCount) matches++;
            }
        }
        return total == 0 ? 1.0 : (double) matches / total;
    }

    private double callScore(String original, String reconstructed) {
        // Simple: count '(' occurrences as a proxy for method calls
        int origCalls = countOccurrences(original, "(");
        int reconCalls = countOccurrences(reconstructed, "(");
        if (origCalls == 0 && reconCalls == 0) return 1.0;
        return 1.0 - Math.abs(origCalls - reconCalls) / (double) Math.max(origCalls, reconCalls);
    }

    private double expressionScore(String original, String reconstructed) {
        int origOps = countOccurrences(original, "+") + countOccurrences(original, "-")
                + countOccurrences(original, "*") + countOccurrences(original, "/");
        int reconOps = countOccurrences(reconstructed, "+") + countOccurrences(reconstructed, "-")
                + countOccurrences(reconstructed, "*") + countOccurrences(reconstructed, "/");
        if (origOps == 0 && reconOps == 0) return 1.0;
        return 1.0 - Math.abs(origOps - reconOps) / (double) Math.max(origOps + 1, reconOps + 1);
    }

    private double declarationScore(String original, String reconstructed) {
        // Count type keywords as proxy
        int origTypes = countOccurrences(original, "int ") + countOccurrences(original, "String ")
                + countOccurrences(original, "boolean ");
        int reconTypes = countOccurrences(reconstructed, "int ") + countOccurrences(reconstructed, "String ")
                + countOccurrences(reconstructed, "boolean ");
        if (origTypes == 0 && reconTypes == 0) return 1.0;
        return 1.0 - Math.abs(origTypes - reconTypes) / (double) Math.max(origTypes + 1, reconTypes + 1);
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
```

- [ ] **Step 2: Create test**

```java
package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlowRoundTripVerifierTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @BeforeAll
    static void setUp() {
        var typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(FIXTURES));
        var config = new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver));
        StaticJavaParser.setConfiguration(config);
    }

    @Test
    void batchVerificationProducesReport() {
        var verifier = new FlowRoundTripVerifier();
        var report = verifier.verifyProject(List.of(FIXTURES));
        assertThat(report.totalMethods()).isGreaterThan(0);
        // At least some should be parseable
        assertThat(report.parseable()).isGreaterThan(0);
        System.out.println("Total: " + report.totalMethods()
                + ", Parseable: " + report.parseable()
                + ", Avg score: " + String.format("%.2f", report.averageScore()));
        for (var worst : report.worstMethods()) {
            System.out.println("  " + worst.methodSignature()
                    + " -> parseable=" + worst.parseable()
                    + " score=" + String.format("%.2f", worst.similarityScore()));
        }
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests '*FlowRoundTripVerifierTest*' 2>&1 | tail -30`
Expected: Test passes, output shows parseable counts and scores.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/FlowRoundTripVerifier.java \
        src/test/java/com/github/sckwoky/typegraph/flow/FlowRoundTripVerifierTest.java
git commit -m "feat: FlowRoundTripVerifier with batch verification and similarity scoring"
```

---

## Phase 5: Integration and Polish

### Task 13: Update FlowJsonExporter for attributes map

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/export/FlowJsonExporter.java`

- [ ] **Step 1: Add attributes to node JSON rendering**

In `FlowJsonExporter.renderNode()`, add attributes map serialization. After the existing node data fields, add:
```java
        // In renderNode(), add after existing fields:
        if (!n.attributes().isEmpty()) {
            for (var entry : n.attributes().entrySet()) {
                fields.add(jsonStr(entry.getKey()) + ":" + jsonStr(entry.getValue()));
            }
        }
        if (n.stmtOrdinal() >= 0) {
            fields.add("\"stmtOrdinal\":" + n.stmtOrdinal());
        }
```

(The exact integration depends on the existing structure of `renderNode()` — adapt to the existing field-building pattern.)

- [ ] **Step 2: Run all tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/export/FlowJsonExporter.java
git commit -m "feat: serialize FlowNode attributes and stmtOrdinal in JSON export"
```

---

### Task 14: End-to-end verification test and cleanup

**Files:**
- Modify: `src/test/java/com/github/sckwoky/typegraph/flow/FlowCodeReconstructorTest.java`

- [ ] **Step 1: Add comprehensive round-trip tests**

```java
    @Test
    void whileLoopReconstructsWithCondition() {
        var graph = findMethod("com.example.ReconstructionFixture", "whileLoop");
        var reconstructor = new FlowCodeReconstructor(graph);
        var code = reconstructor.reconstruct();
        assertThat(code).contains("while");
        assertThat(code).contains("<");
        assertThat(code).contains("return");
        var result = StaticJavaParser.parse("class __V { " + code + " }");
        assertThat(result.getType(0).getMethods()).hasSize(1);
    }

    @Test
    void ternaryReconstructsWithCondition() {
        var graph = findMethod("com.example.ReconstructionFixture", "ternary");
        var reconstructor = new FlowCodeReconstructor(graph);
        var code = reconstructor.reconstruct();
        assertThat(code).contains("?");
        assertThat(code).contains(":");
    }

    @Test
    void voidReturnReconstructs() {
        var graph = findMethod("com.example.ReconstructionFixture", "voidReturn");
        var reconstructor = new FlowCodeReconstructor(graph);
        var code = reconstructor.reconstruct();
        assertThat(code).contains("return;");
    }

    @Test
    void allFixtureMethodsProduceParseableCode() {
        for (var entry : entries) {
            if (!entry.declaringType().startsWith("com.example.ReconstructionFixture")) continue;
            var reconstructor = new FlowCodeReconstructor(entry.graph());
            var code = reconstructor.reconstruct();
            try {
                StaticJavaParser.parse("class __V { " + code + " }");
            } catch (Exception e) {
                throw new AssertionError("Failed to parse reconstructed code for "
                        + entry.displayName() + ":\n" + code, e);
            }
        }
    }
```

- [ ] **Step 2: Run all tests**

Run: `./gradlew test 2>&1 | tail -30`
Expected: All tests pass.

- [ ] **Step 3: Remove deprecated mkTemp() bridge if no longer used**

Check if `mkTemp()` is still called anywhere. If so, replace remaining calls with proper expression nodes. If not, delete it.

Run: `grep -n 'mkTemp' src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java`

- [ ] **Step 4: Final test run**

Run: `./gradlew test 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/github/sckwoky/typegraph/flow/FlowCodeReconstructorTest.java \
        src/main/java/com/github/sckwoky/typegraph/flow/MethodFlowBuilder.java
git commit -m "test: comprehensive round-trip verification tests, remove mkTemp bridge"
```
