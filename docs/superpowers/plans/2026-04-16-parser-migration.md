# Parser Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace JavaParser with a two-layer tree-sitter + Eclipse JDT architecture for the flow graph layer, with abstraction interfaces for backend swapping.

**Architecture:** Create `SourceIndexer` (structural scanning) and `MethodBodyAnalyzer` (deep AST analysis) interfaces. Implement JDT backend as primary, wrap existing JavaParser code as fallback. Tree-sitter is optional for fast indexing. `FlowGraphService` orchestrates everything, replacing `ProjectFlowGraphs`.

**Tech Stack:** Java 25 (preview), Eclipse JDT Core, tree-sitter (jtreesitter), JGraphT 1.5.2, JUnit 5, AssertJ

---

## File Structure

### New files (in `src/main/java/com/github/sckwoky/typegraph/flow/`)
- `spi/SourceIndexer.java` — interface for structural scanning
- `spi/MethodBodyAnalyzer.java` — interface for deep analysis
- `spi/ExecutableInfo.java` — record for method/constructor metadata
- `spi/ClassInfo.java` — record for class metadata
- `spi/ProjectIndex.java` — record for project-wide index
- `spi/ParamInfo.java` — record for parameter metadata
- `spi/FieldInfo.java` — record for field metadata
- `spi/ExecutableKind.java` — enum METHOD/CONSTRUCTOR
- `jdt/JdtEnvironment.java` — JDT ASTParser setup helper
- `jdt/JdtSourceIndexer.java` — JDT-based SourceIndexer
- `jdt/JdtMethodBodyAnalyzer.java` — JDT-based MethodBodyAnalyzer
- `ts/TreeSitterSourceIndexer.java` — tree-sitter SourceIndexer (optional)
- `JavaParserMethodBodyAnalyzer.java` — wraps existing MethodFlowBuilder
- `FlowGraphService.java` — orchestrator replacing ProjectFlowGraphs

### Modified files
- `build.gradle` — add JDT + tree-sitter dependencies
- `flow/model/BinaryOperator.java` — add `fromJdt()` bridge
- `flow/model/UnaryOperator.java` — add `fromJdt()` bridge
- `flow/model/AssignOperator.java` — add `fromJdt()` bridge
- `flow/FieldIndex.java` — add parser-agnostic constructor
- `cli/Main.java` — switch `handleFlowGraphs()` to FlowGraphService

### New test files
- `src/test/java/com/github/sckwoky/typegraph/flow/FlowGraphServiceTest.java`
- `src/test/java/com/github/sckwoky/typegraph/flow/jdt/JdtMethodBodyAnalyzerTest.java`

---

## Phase 1: Foundation

### Task 1: Add dependencies and create model records + interfaces

**Files:**
- Modify: `build.gradle`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/spi/ExecutableKind.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/spi/ParamInfo.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/spi/FieldInfo.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/spi/ExecutableInfo.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/spi/ClassInfo.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/spi/ProjectIndex.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/spi/SourceIndexer.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/spi/MethodBodyAnalyzer.java`

- [ ] **Step 1: Add JDT and tree-sitter dependencies to build.gradle**

Add after the existing `javaparser-symbol-solver-core` dependency:
```groovy
    // Eclipse JDT Core (standalone, no Eclipse IDE needed)
    implementation 'org.eclipse.jdt:org.eclipse.jdt.core:3.40.0'
    implementation 'org.eclipse.jdt:ecj:3.40.0'

    // Tree-sitter (optional — requires native libs)
    implementation('io.github.tree-sitter:jtreesitter:0.24.4') { transitive = false }
```

Also add to the `JavaExec` and `Test` JVM args:
```groovy
tasks.withType(JavaExec).configureEach {
    jvmArgs '--enable-preview', '--enable-native-access=ALL-UNNAMED'
}

tasks.withType(Test).configureEach {
    jvmArgs '--enable-preview', '--enable-native-access=ALL-UNNAMED'
}
```

- [ ] **Step 2: Verify dependencies resolve**

Run: `./gradlew dependencies --configuration compileClasspath 2>&1 | grep -E 'jdt|tree-sitter|ecj'`

If versions don't resolve, search Maven Central for available versions and adjust. JDT needs to support Java 25 — look for the latest stable release.

- [ ] **Step 3: Create the spi package with model records**

Create directory: `src/main/java/com/github/sckwoky/typegraph/flow/spi/`

`ExecutableKind.java`:
```java
package com.github.sckwoky.typegraph.flow.spi;

public enum ExecutableKind { METHOD, CONSTRUCTOR }
```

`ParamInfo.java`:
```java
package com.github.sckwoky.typegraph.flow.spi;

public record ParamInfo(String name, String type) {}
```

`FieldInfo.java`:
```java
package com.github.sckwoky.typegraph.flow.spi;

public record FieldInfo(String name, String type) {}
```

`ExecutableInfo.java`:
```java
package com.github.sckwoky.typegraph.flow.spi;

import java.nio.file.Path;
import java.util.List;

/** Describes a method or constructor in a parsed source file. */
public record ExecutableInfo(
        ExecutableKind kind,
        String name,              // method name or "<init>"
        String declaringType,     // FQN of declaring class
        List<ParamInfo> parameters,
        String returnType,        // null for constructors
        Path file,                // source file path
        int startLine,
        int endLine
) {}
```

`ClassInfo.java`:
```java
package com.github.sckwoky.typegraph.flow.spi;

import java.nio.file.Path;
import java.util.List;

public record ClassInfo(
        String fqn,
        Path file,
        int startLine,
        int endLine,
        List<ExecutableInfo> executables,
        List<FieldInfo> fields
) {}
```

`ProjectIndex.java`:
```java
package com.github.sckwoky.typegraph.flow.spi;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public record ProjectIndex(
        Map<String, ClassInfo> classesByFqn,
        Map<Path, List<ClassInfo>> classesByFile
) {
    public Optional<ClassInfo> findClass(String fqn) {
        return Optional.ofNullable(classesByFqn.get(fqn));
    }

    public List<ExecutableInfo> allExecutables() {
        return classesByFqn.values().stream()
                .flatMap(c -> c.executables().stream())
                .toList();
    }
}
```

- [ ] **Step 4: Create the interfaces**

`SourceIndexer.java`:
```java
package com.github.sckwoky.typegraph.flow.spi;

import java.nio.file.Path;
import java.util.List;

/** Scans source roots and produces a structural index of the project. */
public interface SourceIndexer {
    ProjectIndex indexProject(List<Path> sourceRoots);
}
```

`MethodBodyAnalyzer.java`:
```java
package com.github.sckwoky.typegraph.flow.spi;

import com.github.sckwoky.typegraph.flow.MethodFlowGraph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes method/constructor bodies and produces flow graphs.
 * Each implementation handles its own parsing and type resolution.
 */
public interface MethodBodyAnalyzer {

    /** Analyze a single executable. Returns null if analysis fails. */
    MethodFlowGraph analyze(ExecutableInfo executable);

    /**
     * Batch-analyze all executables from one file.
     * Implementations should parse the file once and locate each executable by signature.
     * Returns a list parallel to {@code executables} — null entries mean analysis failed.
     */
    default List<MethodFlowGraph> analyzeFile(Path file, List<ExecutableInfo> executables) {
        var results = new ArrayList<MethodFlowGraph>(executables.size());
        for (var exec : executables) {
            try {
                results.add(analyze(exec));
            } catch (Exception e) {
                System.err.println("Failed to analyze " + exec.declaringType() + "#" + exec.name() + ": " + e.getMessage());
                results.add(null);
            }
        }
        return results;
    }
}
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add build.gradle src/main/java/com/github/sckwoky/typegraph/flow/spi/
git commit -m "feat: add JDT/tree-sitter deps, create SPI interfaces and model records"
```

---

## Phase 2: Wrap Existing Code

### Task 2: Generalize FieldIndex and create JavaParserMethodBodyAnalyzer

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/FieldIndex.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/JavaParserMethodBodyAnalyzer.java`

- [ ] **Step 1: Add parser-agnostic constructor to FieldIndex**

Add a second constructor to `FieldIndex.java` that takes a list of `FieldInfo`:
```java
    /** Parser-agnostic constructor from pre-extracted field info. */
    public FieldIndex(List<com.github.sckwoky.typegraph.flow.spi.FieldInfo> fields) {
        this.nameToType = new LinkedHashMap<>();
        for (var f : fields) {
            nameToType.put(f.name(), f.type());
        }
    }
```

Keep the existing JavaParser constructor unchanged.

- [ ] **Step 2: Create JavaParserMethodBodyAnalyzer**

```java
package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.sckwoky.typegraph.flow.spi.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Wraps the existing {@link MethodFlowBuilder} behind the
 * {@link MethodBodyAnalyzer} interface. Used as a fallback when JDT is unavailable.
 */
public class JavaParserMethodBodyAnalyzer implements MethodBodyAnalyzer {

    @Override
    public MethodFlowGraph analyze(ExecutableInfo executable) {
        try {
            var cu = StaticJavaParser.parse(executable.file());
            for (var type : cu.findAll(TypeDeclaration.class)) {
                String fqn = type.getFullyQualifiedName().orElse(type.getNameAsString());
                if (!fqn.equals(executable.declaringType())) continue;

                var fields = new FieldIndex(type);
                var builder = new MethodFlowBuilder(fqn, fields);

                if (executable.kind() == ExecutableKind.METHOD) {
                    for (var md : type.getMethods()) {
                        if (md.getNameAsString().equals(executable.name())
                                && md.getBegin().map(p -> p.line).orElse(-1) == executable.startLine()) {
                            return builder.build(md);
                        }
                    }
                } else {
                    for (var cd : type.getConstructors()) {
                        if (cd.getBegin().map(p -> p.line).orElse(-1) == executable.startLine()) {
                            return builder.build(cd);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to parse " + executable.file() + ": " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<MethodFlowGraph> analyzeFile(Path file, List<ExecutableInfo> executables) {
        // Parse file once, build all graphs
        var results = new ArrayList<MethodFlowGraph>(executables.size());
        try {
            var cu = StaticJavaParser.parse(file);
            for (var exec : executables) {
                try {
                    MethodFlowGraph graph = null;
                    for (var type : cu.findAll(TypeDeclaration.class)) {
                        String fqn = type.getFullyQualifiedName().orElse(type.getNameAsString());
                        if (!fqn.equals(exec.declaringType())) continue;

                        var fields = new FieldIndex(type);
                        var builder = new MethodFlowBuilder(fqn, fields);

                        if (exec.kind() == ExecutableKind.METHOD) {
                            for (var md : type.getMethods()) {
                                if (md.getNameAsString().equals(exec.name())
                                        && md.getBegin().map(p -> p.line).orElse(-1) == exec.startLine()) {
                                    graph = builder.build(md);
                                    break;
                                }
                            }
                        } else {
                            for (var cd : type.getConstructors()) {
                                if (cd.getBegin().map(p -> p.line).orElse(-1) == exec.startLine()) {
                                    graph = builder.build(cd);
                                    break;
                                }
                            }
                        }
                    }
                    results.add(graph);
                } catch (Exception e) {
                    System.err.println("Failed to analyze " + exec.declaringType() + "#" + exec.name() + ": " + e.getMessage());
                    results.add(null);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to parse " + file + ": " + e.getMessage());
            for (int i = 0; i < executables.size(); i++) results.add(null);
        }
        return results;
    }
}
```

- [ ] **Step 3: Verify compilation and tests**

Run: `./gradlew test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/FieldIndex.java \
        src/main/java/com/github/sckwoky/typegraph/flow/JavaParserMethodBodyAnalyzer.java
git commit -m "feat: generalize FieldIndex, create JavaParserMethodBodyAnalyzer wrapper"
```

---

### Task 3: Create FlowGraphService and JDT-based SourceIndexer

**Files:**
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/FlowGraphService.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/jdt/JdtEnvironment.java`
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/jdt/JdtSourceIndexer.java`
- Create: `src/test/java/com/github/sckwoky/typegraph/flow/FlowGraphServiceTest.java`

- [ ] **Step 1: Create JdtEnvironment helper**

```java
package com.github.sckwoky.typegraph.flow.jdt;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates JDT ASTParser configuration for reuse across
 * indexer and analyzer.
 */
public class JdtEnvironment {

    private final String[] classpathEntries;
    private final String[] sourceRoots;
    private final String[] encodings;
    private final Map<String, String> compilerOptions;

    public JdtEnvironment(List<Path> sourceRoots, List<Path> classpathEntries) {
        this.sourceRoots = sourceRoots.stream()
                .map(Path::toString).toArray(String[]::new);
        this.classpathEntries = classpathEntries.stream()
                .map(Path::toString).toArray(String[]::new);
        this.encodings = new String[this.sourceRoots.length];
        java.util.Arrays.fill(this.encodings, "UTF-8");

        this.compilerOptions = Map.of(
                JavaCore.COMPILER_SOURCE, "25",
                JavaCore.COMPILER_COMPLIANCE, "25",
                JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "25",
                JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, JavaCore.ENABLED
        );
    }

    /** Create a fresh ASTParser with bindings enabled. */
    public ASTParser newParser() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setEnvironment(classpathEntries, sourceRoots, encodings, true);
        parser.setCompilerOptions(compilerOptions);
        return parser;
    }

    public String[] sourceRoots() { return sourceRoots; }
    public String[] classpathEntries() { return classpathEntries; }
}
```

- [ ] **Step 2: Create JdtSourceIndexer**

```java
package com.github.sckwoky.typegraph.flow.jdt;

import com.github.sckwoky.typegraph.flow.spi.*;
import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Indexes a project using Eclipse JDT ASTParser.
 * Parses each .java file and extracts class/method/field structure with bindings.
 */
public class JdtSourceIndexer implements SourceIndexer {

    private final JdtEnvironment environment;

    public JdtSourceIndexer(JdtEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public ProjectIndex indexProject(List<Path> sourceRoots) {
        var classesByFqn = new LinkedHashMap<String, ClassInfo>();
        var classesByFile = new LinkedHashMap<Path, List<ClassInfo>>();

        for (var root : sourceRoots) {
            try (var stream = Files.walk(root)) {
                var javaFiles = stream.filter(p -> p.toString().endsWith(".java")).toList();
                for (var file : javaFiles) {
                    try {
                        var classes = indexFile(file);
                        if (!classes.isEmpty()) {
                            classesByFile.put(file, classes);
                            for (var cls : classes) {
                                classesByFqn.put(cls.fqn(), cls);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to index " + file + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to walk source root " + root + ": " + e.getMessage());
            }
        }

        return new ProjectIndex(classesByFqn, classesByFile);
    }

    private List<ClassInfo> indexFile(Path file) throws IOException {
        var source = Files.readString(file);
        var parser = environment.newParser();
        parser.setSource(source.toCharArray());
        parser.setUnitName(file.getFileName().toString());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);

        var cu = (CompilationUnit) parser.createAST(null);
        var result = new ArrayList<ClassInfo>();

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                extractType(node, file, cu, result);
                return true; // visit nested types
            }
        });

        return result;
    }

    private void extractType(TypeDeclaration node, Path file, CompilationUnit cu,
                             List<ClassInfo> result) {
        ITypeBinding binding = node.resolveBinding();
        String fqn = binding != null ? binding.getQualifiedName() : node.getName().getIdentifier();

        int startLine = cu.getLineNumber(node.getStartPosition());
        int endLine = cu.getLineNumber(node.getStartPosition() + node.getLength() - 1);

        // Executables
        var executables = new ArrayList<ExecutableInfo>();
        for (var md : node.getMethods()) {
            if (md.isConstructor()) {
                executables.add(extractConstructor(md, fqn, file, cu));
            } else {
                executables.add(extractMethod(md, fqn, file, cu));
            }
        }

        // Fields
        var fields = new ArrayList<FieldInfo>();
        for (var fd : node.getFields()) {
            for (var frag : fd.fragments()) {
                if (frag instanceof VariableDeclarationFragment vdf) {
                    String fieldType = fd.getType().toString();
                    fields.add(new FieldInfo(vdf.getName().getIdentifier(), fieldType));
                }
            }
        }

        result.add(new ClassInfo(fqn, file, startLine, endLine, executables, fields));
    }

    private ExecutableInfo extractMethod(MethodDeclaration md, String declaringType,
                                         Path file, CompilationUnit cu) {
        var params = new ArrayList<ParamInfo>();
        for (var p : md.parameters()) {
            if (p instanceof SingleVariableDeclaration svd) {
                params.add(new ParamInfo(svd.getName().getIdentifier(), svd.getType().toString()));
            }
        }
        String returnType = md.getReturnType2() != null ? md.getReturnType2().toString() : "void";
        int startLine = cu.getLineNumber(md.getStartPosition());
        int endLine = cu.getLineNumber(md.getStartPosition() + md.getLength() - 1);

        return new ExecutableInfo(ExecutableKind.METHOD, md.getName().getIdentifier(),
                declaringType, params, returnType, file, startLine, endLine);
    }

    private ExecutableInfo extractConstructor(MethodDeclaration cd, String declaringType,
                                              Path file, CompilationUnit cu) {
        var params = new ArrayList<ParamInfo>();
        for (var p : cd.parameters()) {
            if (p instanceof SingleVariableDeclaration svd) {
                params.add(new ParamInfo(svd.getName().getIdentifier(), svd.getType().toString()));
            }
        }
        int startLine = cu.getLineNumber(cd.getStartPosition());
        int endLine = cu.getLineNumber(cd.getStartPosition() + cd.getLength() - 1);

        return new ExecutableInfo(ExecutableKind.CONSTRUCTOR, "<init>",
                declaringType, params, null, file, startLine, endLine);
    }
}
```

- [ ] **Step 3: Create FlowGraphService**

```java
package com.github.sckwoky.typegraph.flow;

import com.github.sckwoky.typegraph.flow.spi.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Orchestrates flow graph construction using pluggable indexer and analyzer backends.
 * Replaces {@link ProjectFlowGraphs} with a parser-agnostic architecture.
 */
public class FlowGraphService {

    /** Same shape as {@link ProjectFlowGraphs.Entry} for backward compatibility. */
    public record Entry(
            String declaringType,
            String methodName,
            String displayName,
            String packageName,
            MethodFlowGraph graph
    ) {}

    private final SourceIndexer indexer;
    private final MethodBodyAnalyzer analyzer;

    public FlowGraphService(SourceIndexer indexer, MethodBodyAnalyzer analyzer) {
        this.indexer = indexer;
        this.analyzer = analyzer;
    }

    public List<Entry> buildAll(List<Path> sourceRoots, Predicate<String> scope) {
        var index = indexer.indexProject(sourceRoots);
        var results = new ArrayList<Entry>();

        // Group by file for batch parsing
        var byFile = index.allExecutables().stream()
                .filter(e -> scope.test(e.declaringType()))
                .collect(Collectors.groupingBy(ExecutableInfo::file, LinkedHashMap::new, Collectors.toList()));

        for (var fileEntry : byFile.entrySet()) {
            var graphs = analyzer.analyzeFile(fileEntry.getKey(), fileEntry.getValue());
            for (int i = 0; i < fileEntry.getValue().size(); i++) {
                var exec = fileEntry.getValue().get(i);
                var graph = graphs.get(i);
                if (graph != null) {
                    results.add(new Entry(
                            exec.declaringType(),
                            exec.name(),
                            displayName(exec),
                            packageOf(exec.declaringType()),
                            graph
                    ));
                }
            }
        }

        return results;
    }

    private static String displayName(ExecutableInfo exec) {
        var sb = new StringBuilder();
        sb.append(simpleName(exec.declaringType())).append("#").append(exec.name()).append("(");
        for (int i = 0; i < exec.parameters().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(exec.parameters().get(i).type());
        }
        sb.append(")");
        return sb.toString();
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(0, dot) : "";
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
```

- [ ] **Step 4: Write integration test using JavaParser backend**

```java
package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.sckwoky.typegraph.flow.jdt.JdtEnvironment;
import com.github.sckwoky.typegraph.flow.jdt.JdtSourceIndexer;
import com.github.sckwoky.typegraph.flow.model.FlowNodeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlowGraphServiceTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @BeforeAll
    static void setUp() {
        // Configure JavaParser for the fallback analyzer
        var typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(FIXTURES));
        var config = new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver));
        StaticJavaParser.setConfiguration(config);
    }

    @Test
    void javaParserBackendProducesSameResultsAsProjectFlowGraphs() {
        // Old path: ProjectFlowGraphs directly
        var oldEntries = new ProjectFlowGraphs().buildAll(List.of(FIXTURES), t -> true);

        // New path: FlowGraphService with JDT indexer + JavaParser analyzer
        var env = new JdtEnvironment(List.of(FIXTURES), List.of());
        var indexer = new JdtSourceIndexer(env);
        var analyzer = new JavaParserMethodBodyAnalyzer();
        var service = new FlowGraphService(indexer, analyzer);
        var newEntries = service.buildAll(List.of(FIXTURES), t -> true);

        // Should produce same number of methods
        assertThat(newEntries.size()).isEqualTo(oldEntries.size());

        // Every method that existed before should still exist
        var oldNames = oldEntries.stream()
                .map(e -> e.declaringType() + "#" + e.methodName())
                .sorted().toList();
        var newNames = newEntries.stream()
                .map(e -> e.declaringType() + "#" + e.methodName())
                .sorted().toList();
        assertThat(newNames).containsExactlyElementsOf(oldNames);
    }

    @Test
    void serviceProducesNonEmptyGraphs() {
        var env = new JdtEnvironment(List.of(FIXTURES), List.of());
        var indexer = new JdtSourceIndexer(env);
        var analyzer = new JavaParserMethodBodyAnalyzer();
        var service = new FlowGraphService(indexer, analyzer);
        var entries = service.buildAll(List.of(FIXTURES), t -> true);

        assertThat(entries).isNotEmpty();
        for (var entry : entries) {
            assertThat(entry.graph().nodeCount())
                    .as("Graph for " + entry.displayName() + " should have nodes")
                    .isGreaterThan(0);
        }
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass. If JDT dependencies don't resolve, fix versions first.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/jdt/ \
        src/main/java/com/github/sckwoky/typegraph/flow/FlowGraphService.java \
        src/test/java/com/github/sckwoky/typegraph/flow/FlowGraphServiceTest.java
git commit -m "feat: FlowGraphService with JdtSourceIndexer and JavaParser analyzer fallback"
```

---

## Phase 3: JDT Method Body Analyzer

### Task 4: Add JDT bridge methods to operator enums

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/model/BinaryOperator.java`
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/model/UnaryOperator.java`
- Modify: `src/main/java/com/github/sckwoky/typegraph/flow/model/AssignOperator.java`

- [ ] **Step 1: Add fromJdt() to BinaryOperator**

Add import and method:
```java
import org.eclipse.jdt.core.dom.InfixExpression;

    public static BinaryOperator fromJdt(InfixExpression.Operator op) {
        if (op == InfixExpression.Operator.PLUS) return PLUS;
        if (op == InfixExpression.Operator.MINUS) return MINUS;
        if (op == InfixExpression.Operator.TIMES) return MULT;
        if (op == InfixExpression.Operator.DIVIDE) return DIV;
        if (op == InfixExpression.Operator.REMAINDER) return MOD;
        if (op == InfixExpression.Operator.CONDITIONAL_AND) return AND;
        if (op == InfixExpression.Operator.CONDITIONAL_OR) return OR;
        if (op == InfixExpression.Operator.EQUALS) return EQ;
        if (op == InfixExpression.Operator.NOT_EQUALS) return NE;
        if (op == InfixExpression.Operator.LESS) return LT;
        if (op == InfixExpression.Operator.GREATER) return GT;
        if (op == InfixExpression.Operator.LESS_EQUALS) return LE;
        if (op == InfixExpression.Operator.GREATER_EQUALS) return GE;
        if (op == InfixExpression.Operator.AND) return BIT_AND;
        if (op == InfixExpression.Operator.OR) return BIT_OR;
        if (op == InfixExpression.Operator.XOR) return BIT_XOR;
        if (op == InfixExpression.Operator.LEFT_SHIFT) return LSHIFT;
        if (op == InfixExpression.Operator.RIGHT_SHIFT_SIGNED) return RSHIFT;
        if (op == InfixExpression.Operator.RIGHT_SHIFT_UNSIGNED) return URSHIFT;
        throw new IllegalArgumentException("Unknown JDT operator: " + op);
    }
```

- [ ] **Step 2: Add fromJdt() to UnaryOperator**

```java
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;

    public static UnaryOperator fromJdtPrefix(PrefixExpression.Operator op) {
        if (op == PrefixExpression.Operator.NOT) return NOT;
        if (op == PrefixExpression.Operator.MINUS) return NEG;
        if (op == PrefixExpression.Operator.COMPLEMENT) return BIT_NOT;
        if (op == PrefixExpression.Operator.INCREMENT) return PRE_INC;
        if (op == PrefixExpression.Operator.DECREMENT) return PRE_DEC;
        if (op == PrefixExpression.Operator.PLUS) return PLUS;
        throw new IllegalArgumentException("Unknown JDT prefix operator: " + op);
    }

    public static UnaryOperator fromJdtPostfix(PostfixExpression.Operator op) {
        if (op == PostfixExpression.Operator.INCREMENT) return POST_INC;
        if (op == PostfixExpression.Operator.DECREMENT) return POST_DEC;
        throw new IllegalArgumentException("Unknown JDT postfix operator: " + op);
    }
```

- [ ] **Step 3: Add fromJdt() to AssignOperator**

```java
import org.eclipse.jdt.core.dom.Assignment;

    public static AssignOperator fromJdt(Assignment.Operator op) {
        if (op == Assignment.Operator.ASSIGN) return ASSIGN;
        if (op == Assignment.Operator.PLUS_ASSIGN) return PLUS_ASSIGN;
        if (op == Assignment.Operator.MINUS_ASSIGN) return MINUS_ASSIGN;
        if (op == Assignment.Operator.TIMES_ASSIGN) return MULT_ASSIGN;
        if (op == Assignment.Operator.DIVIDE_ASSIGN) return DIV_ASSIGN;
        if (op == Assignment.Operator.REMAINDER_ASSIGN) return MOD_ASSIGN;
        if (op == Assignment.Operator.BIT_AND_ASSIGN) return AND_ASSIGN;
        if (op == Assignment.Operator.BIT_OR_ASSIGN) return OR_ASSIGN;
        if (op == Assignment.Operator.BIT_XOR_ASSIGN) return XOR_ASSIGN;
        if (op == Assignment.Operator.LEFT_SHIFT_ASSIGN) return LSHIFT_ASSIGN;
        if (op == Assignment.Operator.RIGHT_SHIFT_SIGNED_ASSIGN) return RSHIFT_ASSIGN;
        if (op == Assignment.Operator.RIGHT_SHIFT_UNSIGNED_ASSIGN) return URSHIFT_ASSIGN;
        throw new IllegalArgumentException("Unknown JDT assignment operator: " + op);
    }
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/model/BinaryOperator.java \
        src/main/java/com/github/sckwoky/typegraph/flow/model/UnaryOperator.java \
        src/main/java/com/github/sckwoky/typegraph/flow/model/AssignOperator.java
git commit -m "feat: add fromJdt() bridge methods to operator enums"
```

---

### Task 5: Create JdtMethodBodyAnalyzer

This is the core of the migration — a JDT-based implementation of `MethodBodyAnalyzer` that follows the same algorithm as `MethodFlowBuilder` but uses JDT AST types and bindings.

**Files:**
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/jdt/JdtMethodBodyAnalyzer.java`
- Create: `src/test/java/com/github/sckwoky/typegraph/flow/jdt/JdtMethodBodyAnalyzerTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.github.sckwoky.typegraph.flow.jdt;

import com.github.sckwoky.typegraph.flow.FlowGraphService;
import com.github.sckwoky.typegraph.flow.model.FlowNodeKind;
import com.github.sckwoky.typegraph.flow.model.FlowEdgeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdtMethodBodyAnalyzerTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");
    private static List<FlowGraphService.Entry> entries;

    @BeforeAll
    static void setUp() {
        var env = new JdtEnvironment(List.of(FIXTURES), List.of());
        var indexer = new JdtSourceIndexer(env);
        var analyzer = new JdtMethodBodyAnalyzer(env);
        var service = new FlowGraphService(indexer, analyzer);
        entries = service.buildAll(List.of(FIXTURES), t -> true);
    }

    private FlowGraphService.Entry findMethod(String declaring, String name) {
        return entries.stream()
                .filter(e -> e.declaringType().equals(declaring) && e.methodName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "Method not found: " + declaring + "#" + name));
    }

    @Test
    void producesGraphsForAllMethods() {
        assertThat(entries).isNotEmpty();
        for (var entry : entries) {
            assertThat(entry.graph().nodeCount())
                    .as("Graph for " + entry.displayName())
                    .isGreaterThan(0);
        }
    }

    @Test
    void findOrAdoptHasControlStructures() {
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt").graph();
        var branchNodes = graph.branchNodes();
        assertThat(branchNodes).isNotEmpty();
        var loopNodes = graph.loopNodes();
        assertThat(loopNodes).isNotEmpty();
    }

    @Test
    void findOrAdoptHasMultipleReturns() {
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt").graph();
        assertThat(graph.returnNodes()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void binaryExpressionsHaveOperandEdges() {
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt").graph();
        var binOps = graph.nodesOf(FlowNodeKind.BINARY_OP);
        assertThat(binOps).isNotEmpty();
        for (var binOp : binOps) {
            var inKinds = graph.incomingEdgesOf(binOp).stream()
                    .map(e -> e.kind()).toList();
            assertThat(inKinds).containsAnyOf(FlowEdgeKind.LEFT_OPERAND, FlowEdgeKind.RIGHT_OPERAND);
        }
    }

    @Test
    void methodCallsHaveReceiverEdge() {
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt").graph();
        var calls = graph.callNodes();
        var hasReceiver = calls.stream().anyMatch(call ->
                graph.incomingEdgesOf(call).stream().anyMatch(e -> e.kind() == FlowEdgeKind.RECEIVER));
        assertThat(hasReceiver).isTrue();
    }

    @Test
    void conditionEdgesPresent() {
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt").graph();
        for (var branch : graph.branchNodes()) {
            if (branch.controlSubtype() == com.github.sckwoky.typegraph.flow.model.ControlSubtype.IF) {
                var condEdges = graph.incomingEdgesOf(branch).stream()
                        .filter(e -> e.kind() == FlowEdgeKind.CONDITION).toList();
                assertThat(condEdges).as("IF branch should have CONDITION edge").isNotEmpty();
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*JdtMethodBodyAnalyzerTest*' 2>&1 | tail -10`
Expected: FAIL — class JdtMethodBodyAnalyzer doesn't exist yet.

- [ ] **Step 3: Implement JdtMethodBodyAnalyzer**

This class mirrors `MethodFlowBuilder` but uses `org.eclipse.jdt.core.dom.*` types. It's a large class (~700 lines). The structure:

```java
package com.github.sckwoky.typegraph.flow.jdt;

import com.github.sckwoky.typegraph.flow.*;
import com.github.sckwoky.typegraph.flow.model.*;
import com.github.sckwoky.typegraph.flow.spi.*;
import com.github.sckwoky.typegraph.model.MethodSignature;
import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Builds a {@link MethodFlowGraph} from a JDT {@link org.eclipse.jdt.core.dom.MethodDeclaration}.
 * Same algorithm as {@link MethodFlowBuilder} but with JDT AST types and reliable binding resolution.
 */
public class JdtMethodBodyAnalyzer implements MethodBodyAnalyzer {

    private final JdtEnvironment environment;

    // Per-method state (reset on each analyze call)
    private MethodFlowGraph graph;
    private VariableState scope;
    private final Deque<String> enclosingControlStack = new ArrayDeque<>();
    private FlowNode thisRef;
    private FlowNode superRef;
    private int stmtCounter;
    private String declaringTypeFqn;
    private FieldIndex fields;

    public JdtMethodBodyAnalyzer(JdtEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public MethodFlowGraph analyze(ExecutableInfo executable) {
        // Parse the whole file to get bindings
        try {
            var source = Files.readString(executable.file());
            var parser = environment.newParser();
            parser.setSource(source.toCharArray());
            parser.setUnitName(executable.file().getFileName().toString());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            var cu = (CompilationUnit) parser.createAST(null);

            // Find the method/constructor by line number
            var finder = new MethodFinder(executable, cu);
            cu.accept(finder);
            if (finder.result == null) return null;

            return buildGraph(finder.result, finder.declaringType, executable);
        } catch (IOException e) {
            System.err.println("Failed to read " + executable.file() + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public List<MethodFlowGraph> analyzeFile(Path file, List<ExecutableInfo> executables) {
        try {
            var source = Files.readString(file);
            var parser = environment.newParser();
            parser.setSource(source.toCharArray());
            parser.setUnitName(file.getFileName().toString());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            var cu = (CompilationUnit) parser.createAST(null);

            var results = new ArrayList<MethodFlowGraph>(executables.size());
            for (var exec : executables) {
                try {
                    var finder = new MethodFinder(exec, cu);
                    cu.accept(finder);
                    if (finder.result != null) {
                        results.add(buildGraph(finder.result, finder.declaringType, exec));
                    } else {
                        results.add(null);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to analyze " + exec.declaringType() + "#" + exec.name() + ": " + e.getMessage());
                    results.add(null);
                }
            }
            return results;
        } catch (IOException e) {
            System.err.println("Failed to read " + file + ": " + e.getMessage());
            return executables.stream().map(x -> (MethodFlowGraph) null).toList();
        }
    }

    // ─── Graph construction (mirrors MethodFlowBuilder) ────────────────

    private MethodFlowGraph buildGraph(org.eclipse.jdt.core.dom.MethodDeclaration md,
                                        TypeDeclaration declaringType,
                                        ExecutableInfo execInfo) {
        // Reset per-method state
        this.scope = new VariableState();
        this.enclosingControlStack.clear();
        this.thisRef = null;
        this.superRef = null;
        this.stmtCounter = 0;

        // Build field index from declaring type
        var fieldInfos = new ArrayList<com.github.sckwoky.typegraph.flow.spi.FieldInfo>();
        for (var fd : declaringType.getFields()) {
            for (var frag : fd.fragments()) {
                if (frag instanceof VariableDeclarationFragment vdf) {
                    fieldInfos.add(new com.github.sckwoky.typegraph.flow.spi.FieldInfo(
                            vdf.getName().getIdentifier(), fd.getType().toString()));
                }
            }
        }
        this.fields = new FieldIndex(fieldInfos);
        this.declaringTypeFqn = execInfo.declaringType();

        // Build method signature
        var paramTypes = new ArrayList<String>();
        for (var p : md.parameters()) {
            if (p instanceof SingleVariableDeclaration svd) {
                paramTypes.add(svd.getType().toString());
            }
        }
        String returnType = md.getReturnType2() != null ? md.getReturnType2().toString() : declaringTypeFqn;
        String methodName = md.isConstructor() ? "<init>" : md.getName().getIdentifier();
        var sig = new MethodSignature(declaringTypeFqn, methodName, paramTypes, returnType);

        this.graph = new MethodFlowGraph(sig);

        // THIS_REF for instance methods
        if (!isStatic(md)) {
            thisRef = mkNode(FlowNodeKind.THIS_REF, "this", lineOf(md), declaringTypeFqn,
                    null, -1, null, null, null, ControlSubtype.NONE);
        }

        // Parameters
        for (var p : md.parameters()) {
            if (p instanceof SingleVariableDeclaration svd) {
                var pn = mkNode(FlowNodeKind.PARAM, "param " + svd.getName().getIdentifier(),
                        lineOf(svd), svd.getType().toString(),
                        svd.getName().getIdentifier(), 0, null, null, null, ControlSubtype.NONE);
                scope.define(svd.getName().getIdentifier(), pn);
            }
        }

        // Process body
        if (md.getBody() != null) {
            processBlock(md.getBody());
        }

        return graph;
    }

    // ─── Statement processing ──────────────────────────────────────────
    // Mirrors MethodFlowBuilder.processStmt() with JDT types.
    // Each JDT Statement type maps to the corresponding handler.

    private void processBlock(Block block) {
        scope.pushFrame();
        try {
            for (var s : (List<?>) block.statements()) {
                if (s instanceof Statement stmt) processStmt(stmt);
            }
        } finally {
            scope.popFrame();
        }
    }

    private void processStmt(Statement s) {
        if (s instanceof ExpressionStatement es) {
            analyzeExpr(es.getExpression());
        } else if (s instanceof ReturnStatement rs) {
            processReturn(rs);
        } else if (s instanceof IfStatement is) {
            processIf(is);
        } else if (s instanceof Block bs) {
            processBlock(bs);
        } else if (s instanceof WhileStatement ws) {
            processWhile(ws);
        } else if (s instanceof DoStatement ds) {
            processDo(ds);
        } else if (s instanceof ForStatement fs) {
            processFor(fs);
        } else if (s instanceof EnhancedForStatement efs) {
            processForEach(efs);
        } else if (s instanceof TryStatement ts) {
            processTry(ts);
        } else if (s instanceof SwitchStatement ss) {
            processSwitch(ss);
        } else if (s instanceof ThrowStatement ths) {
            var exprNode = analyzeExpr(ths.getExpression());
            var throwNode = mkExpr(FlowNodeKind.THROW, lineOf(ths), null, Map.of());
            if (exprNode != null) graph.addEdge(exprNode, throwNode, FlowEdgeKind.THROW_VALUE);
        } else if (s instanceof SynchronizedStatement ss) {
            var lockNode = analyzeExpr(ss.getExpression());
            var syncNode = mkExpr(FlowNodeKind.SYNCHRONIZED, lineOf(ss), null, Map.of());
            if (lockNode != null) graph.addEdge(lockNode, syncNode, FlowEdgeKind.SYNC_LOCK);
            enclosingControlStack.push(syncNode.id());
            processBlock(ss.getBody());
            enclosingControlStack.pop();
        } else if (s instanceof AssertStatement as) {
            var checkNode = analyzeExpr(as.getExpression());
            var assertNode = mkExpr(FlowNodeKind.ASSERT, lineOf(as), null, Map.of());
            if (checkNode != null) graph.addEdge(checkNode, assertNode, FlowEdgeKind.CONDITION);
            if (as.getMessage() != null) {
                var msgNode = analyzeExpr(as.getMessage());
                if (msgNode != null) graph.addEdge(msgNode, assertNode, FlowEdgeKind.ASSERT_MESSAGE);
            }
        } else if (s instanceof BreakStatement bs) {
            var attrs = new HashMap<String, String>();
            if (bs.getLabel() != null) attrs.put("targetLabel", bs.getLabel().getIdentifier());
            mkExpr(FlowNodeKind.BREAK, lineOf(bs), null, attrs);
        } else if (s instanceof ContinueStatement cs) {
            var attrs = new HashMap<String, String>();
            if (cs.getLabel() != null) attrs.put("targetLabel", cs.getLabel().getIdentifier());
            mkExpr(FlowNodeKind.CONTINUE, lineOf(cs), null, attrs);
        } else if (s instanceof VariableDeclarationStatement vds) {
            for (var frag : (List<?>) vds.fragments()) {
                if (frag instanceof VariableDeclarationFragment vdf) {
                    FlowNode init = vdf.getInitializer() != null ? analyzeExpr(vdf.getInitializer()) : null;
                    var def = mkNode(FlowNodeKind.LOCAL_DEF, vdf.getName().getIdentifier() + " :=",
                            lineOf(vds), vds.getType().toString(),
                            vdf.getName().getIdentifier(), scope.nextVersion(vdf.getName().getIdentifier()),
                            null, null, null, ControlSubtype.NONE);
                    if (init != null) graph.addEdge(init, def, FlowEdgeKind.DATA_DEP, "init");
                    scope.define(vdf.getName().getIdentifier(), def);
                }
            }
        } else if (s instanceof LabeledStatement ls) {
            enclosingControlStack.push("label:" + ls.getLabel().getIdentifier());
            processStmt(ls.getBody());
            enclosingControlStack.pop();
        }
    }

    // ─── Control flow handlers ─────────────────────────────────────────
    // Each mirrors the corresponding method in MethodFlowBuilder.

    private void processReturn(ReturnStatement rs) {
        var ret = mkNode(FlowNodeKind.RETURN, "return", lineOf(rs), null,
                null, -1, null, null, null, ControlSubtype.NONE);
        if (rs.getExpression() != null) {
            var val = analyzeExpr(rs.getExpression());
            if (val != null) graph.addEdge(val, ret, FlowEdgeKind.RETURN_DEP);
        }
    }

    private void processIf(IfStatement is) {
        var condVal = analyzeExpr(is.getExpression());
        var branch = mkControl(FlowNodeKind.BRANCH, ControlSubtype.IF, "if", lineOf(is));
        if (condVal != null) graph.addEdge(condVal, branch, FlowEdgeKind.CONDITION);

        var before = scope.snapshot();
        enclosingControlStack.push(branch.id());

        scope.pushFrame();
        processStmt(is.getThenStatement());
        var thenSnap = scope.snapshot();
        scope.popFrame();
        scope.restoreFromSnapshot(before);

        Map<String, FlowNode> elseSnap;
        if (is.getElseStatement() != null) {
            scope.pushFrame();
            processStmt(is.getElseStatement());
            elseSnap = scope.snapshot();
            scope.popFrame();
            scope.restoreFromSnapshot(before);
        } else {
            elseSnap = before;
        }

        enclosingControlStack.pop();
        var merge = mkControl(FlowNodeKind.MERGE, ControlSubtype.IF, "merge", lineOf(is));
        graph.addEdge(branch, merge, FlowEdgeKind.CONTROL_DEP);
        mergeBranches(before, List.of(thenSnap, elseSnap), merge);
    }

    private void processWhile(WhileStatement ws) {
        var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.WHILE, "while", lineOf(ws));
        var cond = analyzeExpr(ws.getExpression());
        if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.CONDITION);

        var before = scope.snapshot();
        enclosingControlStack.push(loop.id());
        scope.pushFrame();
        processStmt(ws.getBody());
        var after = scope.snapshot();
        scope.popFrame();
        enclosingControlStack.pop();
        scope.restoreFromSnapshot(before);
        mergeBranches(before, List.of(before, after), loop);
    }

    private void processDo(DoStatement ds) {
        var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.DO, "do-while", lineOf(ds));
        var before = scope.snapshot();
        enclosingControlStack.push(loop.id());
        scope.pushFrame();
        processStmt(ds.getBody());
        var after = scope.snapshot();
        scope.popFrame();
        enclosingControlStack.pop();

        var cond = analyzeExpr(ds.getExpression());
        if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.CONDITION);

        scope.restoreFromSnapshot(before);
        mergeBranches(before, List.of(before, after), loop);
    }

    private void processFor(ForStatement fs) {
        scope.pushFrame();
        var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.FOR, "for", lineOf(fs));

        for (var init : (List<?>) fs.initializers()) {
            if (init instanceof Expression expr) {
                var initNode = analyzeExpr(expr);
                if (initNode != null) graph.addEdge(initNode, loop, FlowEdgeKind.LOOP_INIT);
            }
        }

        if (fs.getExpression() != null) {
            var cond = analyzeExpr(fs.getExpression());
            if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.CONDITION);
        }

        var before = scope.snapshot();
        enclosingControlStack.push(loop.id());
        scope.pushFrame();
        processStmt(fs.getBody());

        for (var upd : (List<?>) fs.updaters()) {
            if (upd instanceof Expression expr) {
                var updNode = analyzeExpr(expr);
                if (updNode != null) graph.addEdge(updNode, loop, FlowEdgeKind.LOOP_UPDATE);
            }
        }

        var after = scope.snapshot();
        scope.popFrame();
        enclosingControlStack.pop();
        scope.restoreFromSnapshot(before);
        mergeBranches(before, List.of(before, after), loop);

        scope.popFrame();
    }

    private void processForEach(EnhancedForStatement efs) {
        var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.FOREACH, "foreach", lineOf(efs));
        var iter = analyzeExpr(efs.getExpression());
        if (iter != null) graph.addEdge(iter, loop, FlowEdgeKind.LOOP_ITERABLE);

        var before = scope.snapshot();
        enclosingControlStack.push(loop.id());
        scope.pushFrame();

        var param = efs.getParameter();
        var loopVar = mkNode(FlowNodeKind.LOCAL_DEF, param.getName().getIdentifier(),
                lineOf(efs), param.getType().toString(),
                param.getName().getIdentifier(), scope.nextVersion(param.getName().getIdentifier()),
                null, null, null, ControlSubtype.NONE);
        if (iter != null) graph.addEdge(iter, loopVar, FlowEdgeKind.DATA_DEP, "iter-elem");
        scope.define(param.getName().getIdentifier(), loopVar);

        processStmt(efs.getBody());
        var after = scope.snapshot();

        scope.popFrame();
        enclosingControlStack.pop();
        scope.restoreFromSnapshot(before);
        mergeBranches(before, List.of(before, after), loop);
    }

    private void processTry(TryStatement ts) {
        var tryNode = mkControl(FlowNodeKind.BRANCH, ControlSubtype.TRY, "try", lineOf(ts));
        enclosingControlStack.push(tryNode.id());
        var before = scope.snapshot();

        scope.pushFrame();
        processBlock(ts.getBody());
        var trySnap = scope.snapshot();
        scope.popFrame();
        scope.restoreFromSnapshot(before);

        var allSnaps = new ArrayList<Map<String, FlowNode>>();
        allSnaps.add(trySnap);

        for (var cc : (List<?>) ts.catchClauses()) {
            if (cc instanceof CatchClause clause) {
                var catchNode = mkControl(FlowNodeKind.BRANCH, ControlSubtype.CATCH,
                        "catch " + clause.getException().getType().toString(), lineOf(clause));
                graph.addEdge(tryNode, catchNode, FlowEdgeKind.CONTROL_DEP);
                enclosingControlStack.push(catchNode.id());
                scope.pushFrame();

                var catchParam = mkNode(FlowNodeKind.PARAM,
                        "catch " + clause.getException().getName().getIdentifier(),
                        lineOf(clause), clause.getException().getType().toString(),
                        clause.getException().getName().getIdentifier(), 0,
                        null, null, null, ControlSubtype.NONE);
                scope.define(clause.getException().getName().getIdentifier(), catchParam);

                processBlock(clause.getBody());
                allSnaps.add(scope.snapshot());

                scope.popFrame();
                enclosingControlStack.pop();
                scope.restoreFromSnapshot(before);
            }
        }

        enclosingControlStack.pop();
        var merge = mkControl(FlowNodeKind.MERGE, ControlSubtype.TRY, "try-merge", lineOf(ts));
        graph.addEdge(tryNode, merge, FlowEdgeKind.CONTROL_DEP);
        mergeBranches(before, allSnaps, merge);

        if (ts.getFinally() != null) {
            var fin = mkControl(FlowNodeKind.BRANCH, ControlSubtype.FINALLY, "finally", lineOf(ts));
            graph.addEdge(merge, fin, FlowEdgeKind.CONTROL_DEP);
            enclosingControlStack.push(fin.id());
            scope.pushFrame();
            processBlock(ts.getFinally());
            scope.popFrame();
            enclosingControlStack.pop();
        }
    }

    private void processSwitch(SwitchStatement ss) {
        var sel = analyzeExpr(ss.getExpression());
        var branch = mkControl(FlowNodeKind.BRANCH, ControlSubtype.SWITCH, "switch", lineOf(ss));
        if (sel != null) graph.addEdge(sel, branch, FlowEdgeKind.CONDITION);

        var before = scope.snapshot();
        enclosingControlStack.push(branch.id());

        var caseSnaps = new ArrayList<Map<String, FlowNode>>();
        scope.pushFrame();
        for (var stmt : (List<?>) ss.statements()) {
            if (stmt instanceof Statement s) processStmt(s);
        }
        caseSnaps.add(scope.snapshot());
        scope.popFrame();
        scope.restoreFromSnapshot(before);

        enclosingControlStack.pop();
        var merge = mkControl(FlowNodeKind.MERGE, ControlSubtype.SWITCH, "switch-merge", lineOf(ss));
        graph.addEdge(branch, merge, FlowEdgeKind.CONTROL_DEP);
        if (caseSnaps.isEmpty()) caseSnaps.add(before);
        mergeBranches(before, caseSnaps, merge);
    }

    // ─── Phi merging (identical to MethodFlowBuilder) ──────────────────

    private void mergeBranches(Map<String, FlowNode> before,
                               List<Map<String, FlowNode>> branchSnaps,
                               FlowNode controlMerge) {
        var changed = new LinkedHashSet<String>();
        for (var snap : branchSnaps) {
            for (var entry : snap.entrySet()) {
                var beforeDef = before.get(entry.getKey());
                if (beforeDef != entry.getValue()) changed.add(entry.getKey());
            }
        }
        for (var name : changed) {
            String typeFqn = null;
            for (var snap : branchSnaps) {
                var v = snap.getOrDefault(name, before.get(name));
                if (v != null && v.typeFqn() != null) { typeFqn = v.typeFqn(); break; }
            }
            var phi = mkNode(FlowNodeKind.MERGE_VALUE, "phi(" + name + ")", -1, typeFqn,
                    name, scope.nextVersion(name), null, null, null, ControlSubtype.NONE);
            graph.addEdge(controlMerge, phi, FlowEdgeKind.CONTROL_DEP);
            for (var snap : branchSnaps) {
                var src = snap.getOrDefault(name, before.get(name));
                if (src != null) graph.addEdge(src, phi, FlowEdgeKind.PHI_INPUT);
            }
            scope.update(name, phi);
        }
    }

    // ─── Expression analysis (mirrors MethodFlowBuilder.analyzeExpr) ───

    private FlowNode analyzeExpr(Expression expr) {
        if (expr == null) return null;

        if (expr instanceof SimpleName sn) return analyzeName(sn);
        if (expr instanceof QualifiedName qn) return analyzeQualifiedName(qn);
        if (expr instanceof FieldAccess fa) return analyzeFieldAccess(fa);
        if (expr instanceof ThisExpression) return ensureThisRef();
        if (expr instanceof SuperFieldAccess || expr instanceof SuperMethodInvocation) return ensureSuperRef();
        if (expr instanceof MethodInvocation mi) return analyzeMethodCall(mi);
        if (expr instanceof ClassInstanceCreation cic) return analyzeObjectCreation(cic);
        if (expr instanceof Assignment ae) return analyzeAssign(ae);
        if (expr instanceof InfixExpression ie) return analyzeBinary(ie);
        if (expr instanceof PrefixExpression pe) return analyzePrefix(pe);
        if (expr instanceof PostfixExpression pe) return analyzePostfix(pe);
        if (expr instanceof CastExpression ce) return analyzeCast(ce);
        if (expr instanceof ParenthesizedExpression pe) return analyzeExpr(pe.getExpression());
        if (expr instanceof ConditionalExpression ce) return analyzeTernary(ce);
        if (expr instanceof InstanceofExpression ioe) return analyzeInstanceOf(ioe);
        if (expr instanceof ArrayAccess aa) return analyzeArrayAccess(aa);
        if (expr instanceof ArrayCreation ac) return analyzeArrayCreation(ac);
        if (expr instanceof LambdaExpression le) return analyzeLambda(le);
        if (expr instanceof NumberLiteral || expr instanceof StringLiteral
                || expr instanceof CharacterLiteral || expr instanceof BooleanLiteral
                || expr instanceof NullLiteral || expr instanceof TypeLiteral) {
            return analyzeLiteral(expr);
        }

        // Fallback
        return mkExpr(FlowNodeKind.BINARY_OP, lineOf(expr), null,
                Map.of("operator", "PLUS")); // placeholder
    }

    private FlowNode analyzeBinary(InfixExpression ie) {
        var l = analyzeExpr(ie.getLeftOperand());
        var r = analyzeExpr(ie.getRightOperand());
        var op = BinaryOperator.fromJdt(ie.getOperator());
        var node = mkExpr(FlowNodeKind.BINARY_OP, lineOf(ie), null,
                Map.of("operator", op.name()));
        if (l != null) graph.addEdge(l, node, FlowEdgeKind.LEFT_OPERAND);
        if (r != null) graph.addEdge(r, node, FlowEdgeKind.RIGHT_OPERAND);
        return node;
    }

    private FlowNode analyzePrefix(PrefixExpression pe) {
        var inner = analyzeExpr(pe.getOperand());
        var op = UnaryOperator.fromJdtPrefix(pe.getOperator());
        var node = mkExpr(FlowNodeKind.UNARY_OP, lineOf(pe), null,
                Map.of("operator", op.name()));
        if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.UNARY_OPERAND);
        return node;
    }

    private FlowNode analyzePostfix(PostfixExpression pe) {
        var inner = analyzeExpr(pe.getOperand());
        var op = UnaryOperator.fromJdtPostfix(pe.getOperator());
        var node = mkExpr(FlowNodeKind.UNARY_OP, lineOf(pe), null,
                Map.of("operator", op.name()));
        if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.UNARY_OPERAND);
        return node;
    }

    private FlowNode analyzeCast(CastExpression ce) {
        var inner = analyzeExpr(ce.getExpression());
        var node = mkExpr(FlowNodeKind.CAST, lineOf(ce), ce.getType().toString(),
                Map.of("targetType", ce.getType().toString()));
        if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.CAST_OPERAND);
        return node;
    }

    private FlowNode analyzeInstanceOf(InstanceofExpression ioe) {
        var inner = analyzeExpr(ioe.getLeftOperand());
        var attrs = new HashMap<String, String>();
        attrs.put("targetType", ioe.getRightOperand().toString());
        var node = mkExpr(FlowNodeKind.INSTANCEOF, lineOf(ioe), "boolean", attrs);
        if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.INSTANCEOF_OPERAND);
        return node;
    }

    private FlowNode analyzeTernary(ConditionalExpression ce) {
        var cond = analyzeExpr(ce.getExpression());
        var thenVal = analyzeExpr(ce.getThenExpression());
        var elseVal = analyzeExpr(ce.getElseExpression());
        var node = mkExpr(FlowNodeKind.TERNARY, lineOf(ce),
                thenVal != null ? thenVal.typeFqn() : null, Map.of());
        if (cond != null) graph.addEdge(cond, node, FlowEdgeKind.TERNARY_CONDITION);
        if (thenVal != null) graph.addEdge(thenVal, node, FlowEdgeKind.TERNARY_THEN);
        if (elseVal != null) graph.addEdge(elseVal, node, FlowEdgeKind.TERNARY_ELSE);
        return node;
    }

    private FlowNode analyzeMethodCall(MethodInvocation mi) {
        FlowNode receiver = mi.getExpression() != null ? analyzeExpr(mi.getExpression()) : null;
        var args = new ArrayList<FlowNode>();
        for (var a : (List<?>) mi.arguments()) {
            if (a instanceof Expression expr) args.add(analyzeExpr(expr));
        }

        MethodSignature sig = null;
        CallResolution res = CallResolution.UNRESOLVED;
        String returnType = null;
        IMethodBinding binding = mi.resolveMethodBinding();
        if (binding != null && !binding.isRecovered()) {
            var paramTypes = new ArrayList<String>();
            for (var pt : binding.getParameterTypes()) paramTypes.add(pt.getQualifiedName());
            String declaring = binding.getDeclaringClass().getQualifiedName();
            returnType = binding.getReturnType().getQualifiedName();
            sig = new MethodSignature(declaring, binding.getName(), paramTypes, returnType);
            res = CallResolution.RESOLVED;
        } else if (binding != null) {
            res = CallResolution.PARTIALLY_RESOLVED;
        }

        var callAttrs = new HashMap<String, String>();
        callAttrs.put("methodName", mi.getName().getIdentifier());
        callAttrs.put("callStyle", receiver != null ? CallStyle.METHOD.name() : CallStyle.STATIC.name());
        var call = new FlowNode(graph.nextId(prefixOf(FlowNodeKind.CALL)),
                FlowNodeKind.CALL, mi.getName().getIdentifier() + "()", lineOf(mi),
                returnType, null, -1, sig, res, null, ControlSubtype.NONE,
                currentEnclosing(), callAttrs, stmtCounter++);
        graph.addNode(call);

        if (receiver != null) graph.addEdge(receiver, call, FlowEdgeKind.RECEIVER);
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i) != null) graph.addEdge(args.get(i), call, FlowEdgeKind.ARG_PASS, String.valueOf(i));
        }

        var result = mkNode(FlowNodeKind.CALL_RESULT, mi.getName().getIdentifier() + "→",
                lineOf(mi), returnType, null, -1, sig, res, null, ControlSubtype.NONE);
        graph.addEdge(call, result, FlowEdgeKind.CALL_RESULT_OF);
        return result;
    }

    private FlowNode analyzeObjectCreation(ClassInstanceCreation cic) {
        var args = new ArrayList<FlowNode>();
        for (var a : (List<?>) cic.arguments()) {
            if (a instanceof Expression expr) args.add(analyzeExpr(expr));
        }

        MethodSignature sig = null;
        CallResolution res = CallResolution.UNRESOLVED;
        String returnType = cic.getType().toString();
        IMethodBinding binding = cic.resolveConstructorBinding();
        if (binding != null && !binding.isRecovered()) {
            var paramTypes = new ArrayList<String>();
            for (var pt : binding.getParameterTypes()) paramTypes.add(pt.getQualifiedName());
            String declaring = binding.getDeclaringClass().getQualifiedName();
            returnType = declaring;
            sig = new MethodSignature(declaring, "<init>", paramTypes, declaring);
            res = CallResolution.RESOLVED;
        }

        var callAttrs = new HashMap<String, String>();
        callAttrs.put("methodName", "<init>");
        callAttrs.put("callStyle", CallStyle.CONSTRUCTOR.name());
        var call = new FlowNode(graph.nextId(prefixOf(FlowNodeKind.OBJECT_CREATE)),
                FlowNodeKind.OBJECT_CREATE, "new " + cic.getType().toString(), lineOf(cic),
                returnType, null, -1, sig, res, null, ControlSubtype.NONE,
                currentEnclosing(), callAttrs, stmtCounter++);
        graph.addNode(call);

        for (int i = 0; i < args.size(); i++) {
            if (args.get(i) != null) graph.addEdge(args.get(i), call, FlowEdgeKind.ARG_PASS, String.valueOf(i));
        }
        var result = mkNode(FlowNodeKind.CALL_RESULT, cic.getType().toString(),
                lineOf(cic), returnType, null, -1, sig, res, null, ControlSubtype.NONE);
        graph.addEdge(call, result, FlowEdgeKind.CALL_RESULT_OF);
        return result;
    }

    private FlowNode analyzeAssign(Assignment ae) {
        var rhs = analyzeExpr(ae.getRightHandSide());
        var target = ae.getLeftHandSide();
        if (target instanceof SimpleName sn) {
            String name = sn.getIdentifier();
            if (scope.currentDef(name) != null || !fields.contains(name)) {
                var def = mkNode(FlowNodeKind.LOCAL_DEF, name + ":=", lineOf(ae),
                        rhs != null ? rhs.typeFqn() : null,
                        name, scope.nextVersion(name), null, null, null, ControlSubtype.NONE);
                if (rhs != null) graph.addEdge(rhs, def, FlowEdgeKind.DATA_DEP, "value");
                scope.update(name, def);
                return def;
            } else {
                var fw = mkNode(FlowNodeKind.FIELD_WRITE, "this." + name + ":=",
                        lineOf(ae), fields.typeOf(name), name, -1, null, null,
                        FieldOrigin.THIS, ControlSubtype.NONE);
                if (thisRef != null) graph.addEdge(thisRef, fw, FlowEdgeKind.DATA_DEP, "this");
                if (rhs != null) graph.addEdge(rhs, fw, FlowEdgeKind.DATA_DEP, "value");
                return fw;
            }
        }
        if (target instanceof FieldAccess fa) {
            FieldOrigin origin = fa.getExpression() instanceof ThisExpression ? FieldOrigin.THIS : FieldOrigin.OTHER;
            FlowNode receiver = fa.getExpression() instanceof ThisExpression ? ensureThisRef() : analyzeExpr(fa.getExpression());
            var fw = mkNode(FlowNodeKind.FIELD_WRITE, fa.getName().getIdentifier() + ":=",
                    lineOf(ae), null, fa.getName().getIdentifier(), -1, null, null, origin, ControlSubtype.NONE);
            if (receiver != null) graph.addEdge(receiver, fw, FlowEdgeKind.DATA_DEP, "receiver");
            if (rhs != null) graph.addEdge(rhs, fw, FlowEdgeKind.DATA_DEP, "value");
            return fw;
        }
        return rhs;
    }

    private FlowNode analyzeName(SimpleName sn) {
        String name = sn.getIdentifier();
        var current = scope.currentDef(name);
        if (current != null) return current;
        if (fields.contains(name)) {
            var fr = mkNode(FlowNodeKind.FIELD_READ, "this." + name, lineOf(sn),
                    fields.typeOf(name), name, -1, null, null,
                    FieldOrigin.THIS, ControlSubtype.NONE);
            if (thisRef != null) graph.addEdge(thisRef, fr, FlowEdgeKind.DATA_DEP, "this");
            return fr;
        }
        return mkExpr(FlowNodeKind.BINARY_OP, lineOf(sn), null, Map.of("operator", "PLUS")); // placeholder
    }

    private FlowNode analyzeQualifiedName(QualifiedName qn) {
        var qualifier = analyzeExpr(qn.getQualifier());
        var fr = mkNode(FlowNodeKind.FIELD_READ, qn.getName().getIdentifier(),
                lineOf(qn), null, qn.getName().getIdentifier(), -1, null, null,
                FieldOrigin.OTHER, ControlSubtype.NONE);
        if (qualifier != null) graph.addEdge(qualifier, fr, FlowEdgeKind.DATA_DEP, "receiver");
        return fr;
    }

    private FlowNode analyzeFieldAccess(FieldAccess fa) {
        FieldOrigin origin = fa.getExpression() instanceof ThisExpression ? FieldOrigin.THIS : FieldOrigin.OTHER;
        FlowNode receiver = fa.getExpression() instanceof ThisExpression ? ensureThisRef() : analyzeExpr(fa.getExpression());
        var fr = mkNode(FlowNodeKind.FIELD_READ, fa.getName().getIdentifier(),
                lineOf(fa), null, fa.getName().getIdentifier(), -1, null, null, origin, ControlSubtype.NONE);
        if (receiver != null) graph.addEdge(receiver, fr, FlowEdgeKind.DATA_DEP, "receiver");
        return fr;
    }

    private FlowNode analyzeArrayAccess(ArrayAccess aa) {
        var arr = analyzeExpr(aa.getArray());
        var idx = analyzeExpr(aa.getIndex());
        var node = mkExpr(FlowNodeKind.ARRAY_ACCESS, lineOf(aa), null, Map.of());
        if (arr != null) graph.addEdge(arr, node, FlowEdgeKind.ARRAY_REF);
        if (idx != null) graph.addEdge(idx, node, FlowEdgeKind.ARRAY_INDEX);
        return node;
    }

    private FlowNode analyzeArrayCreation(ArrayCreation ac) {
        var node = mkExpr(FlowNodeKind.ARRAY_CREATE, lineOf(ac), ac.getType().getElementType().toString(),
                Map.of("elementType", ac.getType().getElementType().toString()));
        var dims = ac.dimensions();
        for (int i = 0; i < dims.size(); i++) {
            if (dims.get(i) instanceof Expression dimExpr) {
                var dimNode = analyzeExpr(dimExpr);
                if (dimNode != null) graph.addEdge(dimNode, node, FlowEdgeKind.ARRAY_DIM, String.valueOf(i));
            }
        }
        return node;
    }

    private FlowNode analyzeLambda(LambdaExpression le) {
        var paramNames = new ArrayList<String>();
        var paramTypes = new ArrayList<String>();
        for (var p : (List<?>) le.parameters()) {
            if (p instanceof VariableDeclaration vd) {
                paramNames.add(vd.getName().getIdentifier());
                if (p instanceof SingleVariableDeclaration svd) {
                    paramTypes.add(svd.getType().toString());
                } else {
                    paramTypes.add("var");
                }
            }
        }
        var attrs = new HashMap<String, String>();
        attrs.put("paramNames", String.join(",", paramNames));
        attrs.put("paramTypes", String.join(",", paramTypes));
        return mkExpr(FlowNodeKind.LAMBDA, lineOf(le), null, attrs);
    }

    private FlowNode analyzeLiteral(Expression expr) {
        String value = expr.toString();
        String typeFqn = null;

        if (expr instanceof NumberLiteral nl) {
            String v = nl.getToken();
            typeFqn = v.endsWith("L") || v.endsWith("l") ? "long"
                    : v.contains(".") || v.endsWith("f") || v.endsWith("F") || v.endsWith("d") || v.endsWith("D") ? "double"
                    : "int";
        } else if (expr instanceof StringLiteral) { typeFqn = "java.lang.String"; }
        else if (expr instanceof CharacterLiteral) { typeFqn = "char"; }
        else if (expr instanceof BooleanLiteral) { typeFqn = "boolean"; }
        else if (expr instanceof NullLiteral) { value = "null"; }

        return mkNode(FlowNodeKind.LITERAL, value, lineOf(expr), typeFqn,
                null, -1, null, null, null, ControlSubtype.NONE);
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private FlowNode ensureThisRef() {
        if (thisRef == null) {
            thisRef = mkNode(FlowNodeKind.THIS_REF, "this", -1, declaringTypeFqn,
                    null, -1, null, null, null, ControlSubtype.NONE);
        }
        return thisRef;
    }

    private FlowNode ensureSuperRef() {
        if (superRef == null) {
            superRef = mkNode(FlowNodeKind.SUPER_REF, "super", -1, declaringTypeFqn,
                    null, -1, null, null, null, ControlSubtype.NONE);
        }
        return superRef;
    }

    private FlowNode mkExpr(FlowNodeKind kind, int line, String typeFqn, Map<String, String> attributes) {
        var id = graph.nextId(prefixOf(kind));
        var node = new FlowNode(id, kind, "", line, typeFqn, null, -1,
                null, null, null, ControlSubtype.NONE, currentEnclosing(),
                attributes, stmtCounter++);
        graph.addNode(node);
        return node;
    }

    private FlowNode mkNode(FlowNodeKind kind, String label, int line,
                            String typeFqn, String varName, int varVersion,
                            MethodSignature sig, CallResolution res,
                            FieldOrigin origin, ControlSubtype subtype) {
        var node = new FlowNode(graph.nextId(prefixOf(kind)), kind, label, line,
                typeFqn, varName, varVersion, sig, res, origin, subtype, currentEnclosing());
        graph.addNode(node);
        return node;
    }

    private FlowNode mkControl(FlowNodeKind kind, ControlSubtype subtype, String label, int line) {
        return mkNode(kind, label, line, null, null, -1, null, null, null, subtype);
    }

    private String prefixOf(FlowNodeKind kind) {
        return switch (kind) {
            case PARAM -> "param"; case THIS_REF -> "this"; case SUPER_REF -> "super";
            case FIELD_READ -> "fr"; case FIELD_WRITE -> "fw";
            case LOCAL_DEF -> "def"; case LOCAL_USE -> "use"; case MERGE_VALUE -> "phi";
            case CALL -> "call"; case CALL_RESULT -> "res"; case RETURN -> "ret";
            case BRANCH -> "br"; case MERGE -> "mrg"; case LOOP -> "loop"; case LITERAL -> "lit";
            case BINARY_OP -> "binop"; case UNARY_OP -> "unop"; case CAST -> "cast";
            case INSTANCEOF -> "iof"; case ARRAY_CREATE -> "arrcr"; case ARRAY_ACCESS -> "arrac";
            case TERNARY -> "tern"; case OBJECT_CREATE -> "objcr"; case LAMBDA -> "lam";
            case METHOD_REF -> "mref"; case ASSIGN -> "asgn"; case THROW -> "throw";
            case BREAK -> "brk"; case CONTINUE -> "cont"; case ASSERT -> "assert";
            case SYNCHRONIZED -> "sync"; case SWITCH_CASE -> "case"; case YIELD -> "yield";
        };
    }

    private String currentEnclosing() {
        return enclosingControlStack.isEmpty() ? null : enclosingControlStack.peek();
    }

    private int lineOf(ASTNode node) {
        var cu = (CompilationUnit) node.getRoot();
        return cu.getLineNumber(node.getStartPosition());
    }

    private boolean isStatic(org.eclipse.jdt.core.dom.MethodDeclaration md) {
        return (md.getModifiers() & org.eclipse.jdt.core.dom.Modifier.STATIC) != 0;
    }

    // ─── Method finder ─────────────────────────────────────────────────

    private static class MethodFinder extends ASTVisitor {
        final ExecutableInfo target;
        final CompilationUnit cu;
        org.eclipse.jdt.core.dom.MethodDeclaration result;
        TypeDeclaration declaringType;

        MethodFinder(ExecutableInfo target, CompilationUnit cu) {
            this.target = target;
            this.cu = cu;
        }

        @Override
        public boolean visit(org.eclipse.jdt.core.dom.MethodDeclaration node) {
            int line = cu.getLineNumber(node.getStartPosition());
            boolean nameMatch = target.kind() == ExecutableKind.CONSTRUCTOR
                    ? node.isConstructor()
                    : node.getName().getIdentifier().equals(target.name());
            if (nameMatch && line == target.startLine()) {
                result = node;
                if (node.getParent() instanceof TypeDeclaration td) {
                    declaringType = td;
                }
            }
            return true;
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test 2>&1 | tail -30`
Expected: All tests pass, including the new JdtMethodBodyAnalyzerTest.

Note: If JDT binding resolution fails on the test fixtures, the tests may need adjustment (e.g., JDT may need classpath entries for java.lang). Debug by checking what CallResolution values the JDT analyzer produces vs the JavaParser one.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/jdt/JdtMethodBodyAnalyzer.java \
        src/test/java/com/github/sckwoky/typegraph/flow/jdt/JdtMethodBodyAnalyzerTest.java
git commit -m "feat: JdtMethodBodyAnalyzer — full JDT-based flow graph builder"
```

---

## Phase 4: Tree-sitter (Optional)

### Task 6: Create TreeSitterSourceIndexer

**Files:**
- Create: `src/main/java/com/github/sckwoky/typegraph/flow/ts/TreeSitterSourceIndexer.java`

- [ ] **Step 1: Implement TreeSitterSourceIndexer with tryCreate()**

```java
package com.github.sckwoky.typegraph.flow.ts;

import com.github.sckwoky.typegraph.flow.spi.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Fast structural indexer using tree-sitter.
 * Optional — requires native libs. Use {@link #tryCreate()} to probe availability.
 */
public class TreeSitterSourceIndexer implements SourceIndexer {

    private TreeSitterSourceIndexer() {}

    /**
     * Probe tree-sitter availability. Returns empty if native libs are missing.
     */
    public static Optional<SourceIndexer> tryCreate() {
        try {
            // Attempt to load tree-sitter classes and native lib
            Class.forName("io.github.treesitter.jtreesitter.Language");
            var indexer = new TreeSitterSourceIndexer();
            // Smoke test: parse a trivial snippet
            indexer.parseSnippet("class A {}");
            return Optional.of(indexer);
        } catch (Throwable t) {
            System.err.println("Tree-sitter unavailable: " + t.getMessage() + ". Using JDT indexer as fallback.");
            return Optional.empty();
        }
    }

    private void parseSnippet(String code) {
        // Minimal tree-sitter parse to verify native lib loads.
        // Implementation depends on the exact jtreesitter API.
        // If this throws, tryCreate() catches it and falls back.
        throw new UnsupportedOperationException(
                "Tree-sitter integration requires verification of jtreesitter API at implementation time");
    }

    @Override
    public ProjectIndex indexProject(List<Path> sourceRoots) {
        var classesByFqn = new LinkedHashMap<String, ClassInfo>();
        var classesByFile = new LinkedHashMap<Path, List<ClassInfo>>();

        for (var root : sourceRoots) {
            try (var stream = Files.walk(root)) {
                var javaFiles = stream.filter(p -> p.toString().endsWith(".java")).toList();
                for (var file : javaFiles) {
                    try {
                        var classes = indexFile(file, root);
                        if (!classes.isEmpty()) {
                            classesByFile.put(file, classes);
                            for (var cls : classes) classesByFqn.put(cls.fqn(), cls);
                        }
                    } catch (Exception e) {
                        System.err.println("Tree-sitter failed to index " + file + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to walk " + root + ": " + e.getMessage());
            }
        }

        return new ProjectIndex(classesByFqn, classesByFile);
    }

    private List<ClassInfo> indexFile(Path file, Path sourceRoot) throws IOException {
        // TODO: Implement tree-sitter query-based extraction.
        // This is a stub — the actual implementation requires verifying the jtreesitter API
        // and loading the Java grammar. The queries from the spec:
        //   (class_declaration name: (identifier) @class_name)
        //   (method_declaration type: (_) @return_type name: (identifier) @method_name ...)
        //   (constructor_declaration name: (identifier) @ctor_name ...)
        //   (field_declaration type: (_) @field_type declarator: (variable_declarator name: (identifier) @field_name))
        throw new UnsupportedOperationException("Tree-sitter indexing not yet implemented");
    }
}
```

Note: The tree-sitter implementation is intentionally a stub. The exact `jtreesitter` API needs verification at implementation time — the Maven coordinates and Java FFM API may differ from what's documented. The `tryCreate()` pattern ensures it fails gracefully and the system falls back to JDT.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (the tree-sitter class compiles even if native lib isn't present — it only loads at runtime).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/flow/ts/TreeSitterSourceIndexer.java
git commit -m "feat: TreeSitterSourceIndexer stub with tryCreate() availability probe"
```

---

## Phase 5: CLI Integration

### Task 7: Update Main.java to use FlowGraphService

**Files:**
- Modify: `src/main/java/com/github/sckwoky/typegraph/cli/Main.java`

- [ ] **Step 1: Rewrite handleFlowGraphs()**

Replace the `handleFlowGraphs()` method in Main.java with:

```java
    private int handleFlowGraphs() throws Exception {
        var sourceRoots = SourceScanner.detectSourceRoots(projectDir);

        // Set up JDT environment
        var classpathEntries = noJars ? java.util.List.<Path>of()
                : com.github.sckwoky.typegraph.parsing.TypeSolverFactory.resolveGradleClasspath(projectDir);
        var jdtEnv = new com.github.sckwoky.typegraph.flow.jdt.JdtEnvironment(sourceRoots, classpathEntries);

        // Choose indexer: tree-sitter if available, JDT fallback
        var indexer = com.github.sckwoky.typegraph.flow.ts.TreeSitterSourceIndexer.tryCreate()
                .orElseGet(() -> new com.github.sckwoky.typegraph.flow.jdt.JdtSourceIndexer(jdtEnv));

        // JDT analyzer as primary
        var analyzer = new com.github.sckwoky.typegraph.flow.jdt.JdtMethodBodyAnalyzer(jdtEnv);

        var service = new com.github.sckwoky.typegraph.flow.FlowGraphService(indexer, analyzer);

        java.util.function.Predicate<String> scopePredicate = scope == null || scope.isEmpty()
                ? t -> true
                : t -> t.equals(scope) || t.startsWith(scope + ".");

        var entries = service.buildAll(sourceRoots, scopePredicate);
        System.out.println("Built flow graphs for " + entries.size() + " methods" +
                (scope != null ? " (scope: " + scope + ")" : ""));
        if (entries.isEmpty()) {
            System.err.println("No methods to export. Try removing --scope or check the project path.");
            return 1;
        }

        // Convert to ProjectFlowGraphs.Entry for FlowHtmlExporter compatibility
        var exportEntries = entries.stream()
                .map(e -> new ProjectFlowGraphs.Entry(e.declaringType(), e.methodName(),
                        e.displayName(), e.packageName(), e.graph()))
                .toList();

        String base = output == null || output.isEmpty() ? "flow" : output;
        Path outputDir = Path.of(base);
        new FlowHtmlExporter().export(exportEntries, outputDir);
        System.out.println("Interactive flow viewer exported to: " + outputDir.resolve("index.html"));
        System.out.println("Open in browser: open " + outputDir.resolve("index.html"));
        return 0;
    }
```

- [ ] **Step 2: Run all tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 3: Run CLI manually to verify**

Run: `./gradlew run --args="--no-jars --flow-graphs -o /tmp/flow-jdt src/test/resources/fixtures" 2>&1 | tail -10`
Expected: "Built flow graphs for N methods", output files created.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/sckwoky/typegraph/cli/Main.java
git commit -m "feat: switch CLI flow-graphs to FlowGraphService with JDT backend"
```
