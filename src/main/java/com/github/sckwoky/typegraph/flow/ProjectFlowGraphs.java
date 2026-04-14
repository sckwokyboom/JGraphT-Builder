package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.sckwoky.typegraph.parsing.SourceScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Walks a project's source roots and builds a {@link MethodFlowGraph} for every
 * method and constructor body it can parse, optionally filtered by a scope.
 */
public class ProjectFlowGraphs {

    public record Entry(
            String declaringType,
            String methodName,
            String displayName,
            String packageName,
            MethodFlowGraph graph
    ) {}

    /**
     * @param scopePredicate filter by declaring type FQN; pass {@code t -> true} for all
     */
    public List<Entry> buildAll(List<Path> sourceRoots, Predicate<String> scopePredicate) {
        var entries = new ArrayList<Entry>();
        for (var root : sourceRoots) {
            for (var file : SourceScanner.findJavaFiles(root)) {
                try {
                    var cu = StaticJavaParser.parse(file);
                    for (var td : cu.findAll(TypeDeclaration.class)) {
                        @SuppressWarnings("unchecked")
                        TypeDeclaration<?> typed = (TypeDeclaration<?>) td;
                        var fqnOpt = typed.getFullyQualifiedName();
                        if (fqnOpt.isEmpty()) continue;
                        String fqn = fqnOpt.get();
                        if (!scopePredicate.test(fqn)) continue;
                        processType(typed, fqn, entries);
                    }
                } catch (Exception e) {
                    System.err.println("Warning: failed to process " + file + ": " + e.getMessage());
                }
            }
        }
        return entries;
    }

    private void processType(TypeDeclaration<?> td, String declaringFqn, List<Entry> out) {
        var fields = new FieldIndex(td);
        String pkg = packageOf(declaringFqn);

        if (td instanceof ClassOrInterfaceDeclaration cid) {
            for (var ctor : cid.getConstructors()) {
                tryBuildCtor(ctor, declaringFqn, fields, pkg, out);
            }
        }

        for (var md : td.findAll(MethodDeclaration.class)) {
            var enclosing = md.findAncestor(TypeDeclaration.class);
            if (enclosing.isEmpty() || enclosing.get() != td) continue;
            tryBuildMethod(md, declaringFqn, fields, pkg, out);
        }
    }

    private void tryBuildMethod(MethodDeclaration md, String declaringFqn, FieldIndex fields,
                                String pkg, List<Entry> out) {
        if (md.getBody().isEmpty()) return;
        try {
            var builder = new MethodFlowBuilder(declaringFqn, fields);
            var graph = builder.build(md);
            String display = simpleName(declaringFqn) + "#" + md.getNameAsString() +
                    "(" + paramList(md) + ")";
            out.add(new Entry(declaringFqn, md.getNameAsString(), display, pkg, graph));
        } catch (Exception e) {
            System.err.println("Warning: failed to build flow graph for " +
                    declaringFqn + "#" + md.getNameAsString() + ": " + e.getMessage());
        }
    }

    private void tryBuildCtor(ConstructorDeclaration cd, String declaringFqn, FieldIndex fields,
                              String pkg, List<Entry> out) {
        try {
            var builder = new MethodFlowBuilder(declaringFqn, fields);
            var graph = builder.build(cd);
            String display = simpleName(declaringFqn) + "#<init>(" + ctorParamList(cd) + ")";
            out.add(new Entry(declaringFqn, "<init>", display, pkg, graph));
        } catch (Exception e) {
            System.err.println("Warning: failed to build flow graph for ctor " +
                    declaringFqn + ": " + e.getMessage());
        }
    }

    private static String paramList(MethodDeclaration md) {
        var sb = new StringBuilder();
        for (int i = 0; i < md.getParameters().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(md.getParameters().get(i).getType().asString());
        }
        return sb.toString();
    }

    private static String ctorParamList(ConstructorDeclaration cd) {
        var sb = new StringBuilder();
        for (int i = 0; i < cd.getParameters().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(cd.getParameters().get(i).getType().asString());
        }
        return sb.toString();
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
