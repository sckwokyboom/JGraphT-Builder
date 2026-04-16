# Parser Migration: JavaParser → Tree-sitter + Eclipse JDT

## Problem

JavaParser's symbol solver is unreliable on large projects — method calls frequently resolve to `UNRESOLVED`, type inference is incomplete, and error-tolerant parsing is absent. For a production Agentic IDE (RAG pipelines, query augmentation, agent execution loops), the parsing layer must be stable, performant, and capable of full Java semantics.

## Goal

Replace JavaParser with a two-layer architecture:
- **Tree-sitter** — fast, incremental, error-tolerant structural indexing
- **Eclipse JDT** — full semantic analysis with reliable type/method resolution

Tree-sitter is optional (requires native libs that may not work on all platforms). When unavailable, JDT handles both indexing and analysis.

## Scope

- Migrate the **flow graph layer** (MethodFlowBuilder, ProjectFlowGraphs, FieldIndex) to JDT
- Create **abstraction interfaces** so backends are swappable
- Keep JavaParser as a **fallback** `MethodBodyAnalyzer` implementation
- Type graph layer stays on JavaParser for now — pluggable via the same interfaces later

## Constraints

- Tree-sitter is optional — fallback to JDT when native libs unavailable
- JavaParser remains as fallback MethodBodyAnalyzer
- All existing tests must pass after migration
- FlowNode, FlowEdge, MethodFlowGraph, FlowCodeReconstructor, BackwardSlicer, FlowChainExtractor — unchanged (they don't depend on the parser)

---

## 1. Abstraction Interfaces

### SourceIndexer — fast structural scanning

```java
interface SourceIndexer {
    ProjectIndex indexProject(List<Path> sourceRoots);
}
```

`ProjectIndex` provides fast lookup of project structure without type resolution:

```java
record ProjectIndex(
    Map<String, ClassInfo> classesByFqn,
    Map<Path, List<ClassInfo>> classesByFile
) {
    Optional<ClassInfo> findClass(String fqn);
    List<MethodInfo> allMethods();
}

record ClassInfo(
    String fqn, Path file, int startLine, int endLine,
    List<MethodInfo> methods, List<FieldInfo> fields
)

record MethodInfo(
    String name, String declaringType,
    List<ParamInfo> parameters, String returnType,
    int startLine, int endLine,
    Object astHandle  // opaque: JDT ASTNode or JavaParser MethodDeclaration
)

record ParamInfo(String name, String type)
record FieldInfo(String name, String type)
```

Implementations:
- `TreeSitterSourceIndexer` — primary, fast, incremental, error-tolerant
- JDT fallback — reuses the same `ASTParser` configured for `MethodBodyAnalyzer` (with bindings enabled)

### MethodBodyAnalyzer — deep method analysis → flow graph

```java
interface MethodBodyAnalyzer {
    MethodFlowGraph analyze(MethodInfo method);
}
```

Takes a `MethodInfo` and produces a `MethodFlowGraph`. Each implementation handles parsing and type resolution internally — JDT uses bindings on AST nodes, JavaParser uses its symbol solver. No separate `TypeResolver` interface: resolution strategy is an implementation detail of each analyzer.

If `MethodInfo.astHandle` is present and matches the analyzer's AST type, it's used directly. If null (e.g. when tree-sitter did the indexing), the analyzer parses the file itself using `MethodInfo.file` + `startLine`/`endLine` to locate the method.

Implementations:
- `JdtMethodBodyAnalyzer` — primary, uses JDT `org.eclipse.jdt.core.dom.*` AST with `resolveMethodBinding()` for type resolution
- `JavaParserMethodBodyAnalyzer` — wraps existing `MethodFlowBuilder` + symbol solver, fallback

---

## 2. JDT Integration — JdtMethodBodyAnalyzer

### AST type mappings

| JavaParser | JDT | FlowNode kind |
|---|---|---|
| `BinaryExpr` | `InfixExpression` | BINARY_OP |
| `UnaryExpr` | `PrefixExpression` / `PostfixExpression` | UNARY_OP |
| `CastExpr` | `CastExpression` | CAST |
| `InstanceOfExpr` | `InstanceofExpression` | INSTANCEOF |
| `MethodCallExpr` | `MethodInvocation` | CALL |
| `ObjectCreationExpr` | `ClassInstanceCreation` | OBJECT_CREATE |
| `FieldAccessExpr` | `FieldAccess` / `QualifiedName` | FIELD_READ |
| `ArrayAccessExpr` | `ArrayAccess` | ARRAY_ACCESS |
| `ArrayCreationExpr` | `ArrayCreation` | ARRAY_CREATE |
| `ConditionalExpr` | `ConditionalExpression` | TERNARY |
| `LambdaExpr` | `LambdaExpression` | LAMBDA |
| `MethodReferenceExpr` | `ExpressionMethodReference` / `TypeMethodReference` / `CreationReference` | METHOD_REF |
| `AssignExpr` | `Assignment` | ASSIGN / LOCAL_DEF |
| `VariableDeclarationExpr` | `VariableDeclarationStatement` / `VariableDeclarationExpression` | LOCAL_DEF |
| `IfStmt` | `IfStatement` | BRANCH(IF) |
| `WhileStmt` | `WhileStatement` | LOOP(WHILE) |
| `DoStmt` | `DoStatement` | LOOP(DO) |
| `ForStmt` | `ForStatement` | LOOP(FOR) |
| `ForEachStmt` | `EnhancedForStatement` | LOOP(FOREACH) |
| `TryStmt` | `TryStatement` | BRANCH(TRY) |
| `SwitchStmt` | `SwitchStatement` | BRANCH(SWITCH) |
| `ReturnStmt` | `ReturnStatement` | RETURN |
| `ThrowStmt` | `ThrowStatement` | THROW |
| `SynchronizedStmt` | `SynchronizedStatement` | SYNCHRONIZED |
| `AssertStmt` | `AssertStatement` | ASSERT |
| `BreakStmt` | `BreakStatement` | BREAK |
| `ContinueStmt` | `ContinueStatement` | CONTINUE |
| `SuperExpr` | `SuperFieldAccess` / `SuperMethodInvocation` | SUPER_REF |

### Type resolution via bindings

JDT provides `resolveBinding()` / `resolveMethodBinding()` on AST nodes. Unlike JavaParser's symbol solver, these are reliable because JDT uses a full compiler frontend:

```java
// JDT method call resolution
IMethodBinding binding = methodInvocation.resolveMethodBinding();
if (binding != null) {
    String declaring = binding.getDeclaringClass().getQualifiedName();
    String returnType = binding.getReturnType().getQualifiedName();
    // ... always reliable
    sig = new MethodSignature(declaring, binding.getName(), paramTypes, returnType);
    res = CallResolution.RESOLVED;
} else {
    res = CallResolution.UNRESOLVED;
}
```

### JDT environment setup

```java
ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
parser.setResolveBindings(true);
parser.setBindingsRecovery(true);
parser.setStatementsRecovery(true);
parser.setEnvironment(classpathEntries, sourceRoots, encodings, true);
parser.setCompilerOptions(Map.of(
    JavaCore.COMPILER_SOURCE, "25",
    JavaCore.COMPILER_COMPLIANCE, "25",
    JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "25",
    JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, "enabled"
));
```

### Enum bridge methods

`BinaryOperator`, `UnaryOperator`, `AssignOperator` get a `fromJdt()` method alongside the existing `fromJavaParser()`:

```java
public static BinaryOperator fromJdt(InfixExpression.Operator op) {
    if (op == InfixExpression.Operator.PLUS) return PLUS;
    if (op == InfixExpression.Operator.MINUS) return MINUS;
    // ... etc
}
```

### FieldIndex generalization

Current `FieldIndex` takes JavaParser `TypeDeclaration`. Replace with a constructor that takes `List<FieldInfo>` from `ClassInfo` — parser-agnostic.

### VariableState

Unchanged — works with FlowNode, no parser dependency.

---

## 3. Tree-sitter Layer — TreeSitterSourceIndexer

### Purpose

Fast structural scanning only — no type resolution, no deep AST analysis. Used for:
- Building `ProjectIndex` (class/method/field inventory)
- Future: RAG chunking, incremental change detection

### Java bindings

Uses official `java-tree-sitter` bindings: https://github.com/tree-sitter/java-tree-sitter

```groovy
// build.gradle — optional dependency
implementation('io.github.tree-sitter:tree-sitter:0.24.4') { transitive = false }
implementation('io.github.tree-sitter:tree-sitter-java:0.23.5') { transitive = false }
```

(Exact coordinates and versions to be verified at implementation time.)

### Tree-sitter queries

```scheme
;; Extract class declarations
(class_declaration
  name: (identifier) @class_name)

;; Extract method declarations
(method_declaration
  type: (_) @return_type
  name: (identifier) @method_name
  parameters: (formal_parameters) @params)

;; Extract field declarations
(field_declaration
  type: (_) @field_type
  declarator: (variable_declarator
    name: (identifier) @field_name))
```

### Optionality

`TreeSitterSourceIndexer.tryCreate()` returns `Optional<SourceIndexer>`:
- Tries to load native lib
- If `UnsatisfiedLinkError` or class not found → returns `Optional.empty()`
- Caller falls back to JDT-based indexing

---

## 4. FlowGraphService — Orchestrator

Replaces `ProjectFlowGraphs` as the entry point.

```java
public class FlowGraphService {

    private final SourceIndexer indexer;
    private final MethodBodyAnalyzer analyzer;

    public FlowGraphService(SourceIndexer indexer,
                            MethodBodyAnalyzer analyzer) { ... }

    public List<Entry> buildAll(List<Path> sourceRoots, Predicate<String> scope) {
        var index = indexer.indexProject(sourceRoots);
        var results = new ArrayList<Entry>();
        for (var method : index.allMethods()) {
            if (!scope.test(method.declaringType())) continue;
            var graph = analyzer.analyze(method);
            results.add(new Entry(method.declaringType(), method.name(),
                    displayName(method), packageOf(method), graph));
        }
        return results;
    }
}
```

### Factory / initialization

```java
// In Main.java or a factory class:
SourceIndexer indexer = TreeSitterSourceIndexer.tryCreate()
        .orElseGet(() -> new JdtSourceIndexer(jdtEnvironment));
MethodBodyAnalyzer analyzer = new JdtMethodBodyAnalyzer(jdtEnvironment);
var service = new FlowGraphService(indexer, analyzer);
```

### ProjectFlowGraphs

Becomes a thin backward-compatible wrapper around `FlowGraphService`, or is removed. Its `Entry` record moves to `FlowGraphService`.

### CLI changes

`Main.handleFlowGraphs()` switches from `ProjectFlowGraphs` to `FlowGraphService`. The JDT environment setup replaces the current JavaParser `StaticJavaParser.setConfiguration()` calls.

---

## 5. Dependencies

### New (build.gradle)

```groovy
// Eclipse JDT Core (standalone, no Eclipse IDE needed)
implementation 'org.eclipse.jdt:org.eclipse.jdt.core:3.38.0'
implementation 'org.eclipse.jdt:org.eclipse.jdt.core.compiler.batch:3.38.0'

// Tree-sitter (optional)
implementation('io.github.tree-sitter:tree-sitter:0.24.4') { transitive = false }
implementation('io.github.tree-sitter:tree-sitter-java:0.23.5') { transitive = false }
```

(Exact versions to be verified — JDT 3.38 supports Java 23; for Java 25 preview, may need a newer snapshot or compiler options workaround.)

### Kept

```groovy
// JavaParser — kept as fallback MethodBodyAnalyzer
implementation 'com.github.javaparser:javaparser-symbol-solver-core:3.26.4'
```

### Eventually removable

Once type graph layer migrates too, JavaParser can be removed entirely.

---

## 6. Impact on Existing Code

### Unchanged (no parser dependency)
- `FlowNode`, `FlowEdge`, `FlowNodeKind`, `FlowEdgeKind` — pure model
- `MethodFlowGraph` — JGraphT wrapper
- `FlowCodeReconstructor` — works with FlowNode/FlowEdge
- `FlowRoundTripVerifier` — uses FlowCodeReconstructor + JavaParser for re-parse (keeps JavaParser for parse-check only)
- `BackwardSlicer`, `FlowChainExtractor` — work with MethodFlowGraph
- `FlowJsonExporter`, `FlowHtmlExporter` — work with MethodFlowGraph

### Modified
- `MethodFlowBuilder` → wrapped in `JavaParserMethodBodyAnalyzer` (no code changes, just wrapping)
- `ProjectFlowGraphs` → replaced by `FlowGraphService` (or thin wrapper)
- `FieldIndex` → generalized constructor from `List<FieldInfo>`
- `BinaryOperator`, `UnaryOperator`, `AssignOperator` → add `fromJdt()` methods
- `Main.java` → switch `handleFlowGraphs()` to use `FlowGraphService`
- `build.gradle` → add JDT and tree-sitter dependencies

### New files
- `SourceIndexer.java` (interface)
- `MethodBodyAnalyzer.java` (interface)
- `ProjectIndex.java`, `ClassInfo.java`, `MethodInfo.java`, `ParamInfo.java`, `FieldInfo.java` (records)
- `TreeSitterSourceIndexer.java`
- `JdtSourceIndexer.java`
- `JdtMethodBodyAnalyzer.java`
- `JavaParserMethodBodyAnalyzer.java` (wraps existing MethodFlowBuilder + symbol solver)
- `FlowGraphService.java`

### Tests
- Existing `MethodFlowBuilderTest` continues to work (tests the JavaParser fallback path)
- New `JdtMethodBodyAnalyzerTest` — same test fixtures, same assertions, but through JDT
- New `TreeSitterSourceIndexerTest` — verifies structural extraction
- New `FlowGraphServiceTest` — integration test through the full pipeline
