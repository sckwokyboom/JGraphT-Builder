package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.sckwoky.typegraph.flow.spi.ExecutableInfo;
import com.github.sckwoky.typegraph.flow.spi.ExecutableKind;
import com.github.sckwoky.typegraph.flow.spi.MethodBodyAnalyzer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link MethodBodyAnalyzer} implementation that wraps the existing
 * {@link MethodFlowBuilder} / JavaParser stack.
 *
 * <p>Each {@link #analyzeFile} call parses the file exactly once and then
 * loops over the supplied executables, finding the matching AST node by
 * declaring-type FQN and start line, and delegating to
 * {@link MethodFlowBuilder#build(MethodDeclaration)} or
 * {@link MethodFlowBuilder#build(ConstructorDeclaration)}.
 */
public class JavaParserMethodBodyAnalyzer implements MethodBodyAnalyzer {

    /**
     * Single-executable entry point. Parses the file every time; callers
     * that process multiple executables from the same file should prefer
     * {@link #analyzeFile(Path, List)}.
     */
    @Override
    public MethodFlowGraph analyze(ExecutableInfo executable) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(executable.file());
            return analyzeInCu(cu, executable);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to parse " + executable.file() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses {@code file} once and analyzes all {@code executables} against
     * that single AST, returning one result per executable (null on failure).
     */
    @Override
    public List<MethodFlowGraph> analyzeFile(Path file, List<ExecutableInfo> executables) {
        var results = new ArrayList<MethodFlowGraph>(executables.size());
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(file);
        } catch (Exception e) {
            System.err.println("Failed to parse " + file + ": " + e.getMessage());
            // Return all nulls
            for (int i = 0; i < executables.size(); i++) results.add(null);
            return results;
        }
        for (var exec : executables) {
            try {
                results.add(analyzeInCu(cu, exec));
            } catch (Exception e) {
                System.err.println("Failed to analyze " + exec.declaringType() + "#"
                        + exec.name() + ": " + e.getMessage());
                results.add(null);
            }
        }
        return results;
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private MethodFlowGraph analyzeInCu(CompilationUnit cu, ExecutableInfo exec) {
        TypeDeclaration<?> td = findType(cu, exec.declaringType());
        if (td == null) {
            throw new IllegalArgumentException(
                    "Type not found in file: " + exec.declaringType());
        }
        FieldIndex fieldIndex = new FieldIndex(exec.declaringType() != null ? td : null);

        if (exec.kind() == ExecutableKind.CONSTRUCTOR) {
            ConstructorDeclaration cd = findConstructor(td, exec.name(), exec.startLine());
            if (cd == null) {
                throw new IllegalArgumentException(
                        "Constructor not found: " + exec.declaringType()
                        + " near line " + exec.startLine());
            }
            return new MethodFlowBuilder(exec.declaringType(), fieldIndex).build(cd);
        } else {
            MethodDeclaration md = findMethod(td, exec.name(), exec.startLine());
            if (md == null) {
                throw new IllegalArgumentException(
                        "Method not found: " + exec.declaringType() + "#" + exec.name()
                        + " near line " + exec.startLine());
            }
            return new MethodFlowBuilder(exec.declaringType(), fieldIndex).build(md);
        }
    }

    /**
     * Finds the type declaration whose FQN matches {@code declaringType}.
     * Falls back to simple-name match if FQN is not available on the node.
     */
    private static TypeDeclaration<?> findType(CompilationUnit cu, String declaringType) {
        for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
            var fqnOpt = td.getFullyQualifiedName();
            if (fqnOpt.isPresent() && fqnOpt.get().equals(declaringType)) return td;
        }
        // Fallback: match simple name (last segment of FQN)
        String simpleName = simpleNameOf(declaringType);
        for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
            if (td.getNameAsString().equals(simpleName)) return td;
        }
        return null;
    }

    /**
     * Finds the method with the given name whose start line best matches
     * {@code startLine}. If no line information is available, returns the
     * first method with that name.
     */
    private static MethodDeclaration findMethod(TypeDeclaration<?> td, String name, int startLine) {
        MethodDeclaration best = null;
        for (MethodDeclaration md : td.findAll(MethodDeclaration.class)) {
            // Only look at methods directly owned by td (not nested types)
            var enclosing = md.findAncestor(TypeDeclaration.class);
            if (enclosing.isEmpty() || enclosing.get() != td) continue;
            if (!md.getNameAsString().equals(name)) continue;
            if (startLine < 0) {
                if (best == null) best = md;
                continue;
            }
            int line = md.getBegin().map(p -> p.line).orElse(-1);
            if (line == startLine) return md;
            if (best == null) best = md;
        }
        return best;
    }

    /**
     * Finds the constructor whose start line best matches {@code startLine}.
     */
    private static ConstructorDeclaration findConstructor(TypeDeclaration<?> td,
                                                          String name, int startLine) {
        ConstructorDeclaration best = null;
        for (ConstructorDeclaration cd : td.findAll(ConstructorDeclaration.class)) {
            var enclosing = cd.findAncestor(TypeDeclaration.class);
            if (enclosing.isEmpty() || enclosing.get() != td) continue;
            if (startLine < 0) {
                if (best == null) best = cd;
                continue;
            }
            int line = cd.getBegin().map(p -> p.line).orElse(-1);
            if (line == startLine) return cd;
            if (best == null) best = cd;
        }
        return best;
    }

    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
