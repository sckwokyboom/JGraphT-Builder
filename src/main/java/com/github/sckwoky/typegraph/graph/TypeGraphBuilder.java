package com.github.sckwoky.typegraph.graph;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.sckwoky.typegraph.model.*;
import com.github.sckwoky.typegraph.parsing.*;

import java.nio.file.Path;
import java.util.List;

/**
 * Builds a {@link TypeGraph} from a Java project directory.
 */
public class TypeGraphBuilder {

    private final Path projectDir;
    private boolean resolveJars = true;

    public TypeGraphBuilder(Path projectDir) {
        this.projectDir = projectDir;
    }

    public TypeGraphBuilder resolveJars(boolean resolveJars) {
        this.resolveJars = resolveJars;
        return this;
    }

    public TypeGraph build() {
        var sourceRoots = SourceScanner.detectSourceRoots(projectDir);
        var jarPaths = resolveJars
                ? TypeSolverFactory.resolveGradleClasspath(projectDir)
                : List.<Path>of();

        var typeSolver = TypeSolverFactory.create(sourceRoots, jarPaths);
        var config = new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.BLEEDING_EDGE)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));

        return doBuild(sourceRoots, config);
    }

    /**
     * Builds a TypeGraph from pre-parsed source roots with a pre-configured type solver.
     * Useful for testing.
     */
    public static TypeGraph buildFromSourceRoots(List<Path> sourceRoots,
                                                  ParserConfiguration config) {
        return doBuild(sourceRoots, config);
    }

    private static TypeGraph doBuild(List<Path> sourceRoots, ParserConfiguration config) {
        StaticJavaParser.setConfiguration(config);
        var typeGraph = new TypeGraph();

        for (var root : sourceRoots) {
            for (var file : SourceScanner.findJavaFiles(root)) {
                try {
                    var cu = StaticJavaParser.parse(file);
                    extractRelationships(cu, typeGraph);
                    extractTypeKinds(cu, typeGraph);
                } catch (Exception e) {
                    System.err.println("Warning: failed to process " + file + ": " + e.getMessage());
                }
            }
        }

        return typeGraph;
    }

    private static void extractRelationships(CompilationUnit cu, TypeGraph typeGraph) {
        var extractor = new TypeRelationshipExtractor();
        extractor.visit(cu, null);

        for (var rel : extractor.getRelationships()) {
            typeGraph.addRelationship(
                    rel.sourceType(), TypeKind.EXTERNAL,
                    rel.targetType(), TypeKind.EXTERNAL,
                    rel.kind(), rel.signature()
            );
        }
    }

    private static void extractTypeKinds(CompilationUnit cu, TypeGraph typeGraph) {
        cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .forEach(decl -> {
                    var fqn = decl.getFullyQualifiedName().orElse(null);
                    if (fqn != null) {
                        var kind = decl.isInterface() ? TypeKind.INTERFACE : TypeKind.CLASS;
                        typeGraph.getOrCreateVertex(fqn, kind);
                    }
                });
        cu.findAll(com.github.javaparser.ast.body.EnumDeclaration.class)
                .forEach(decl -> {
                    var fqn = decl.getFullyQualifiedName().orElse(null);
                    if (fqn != null) {
                        typeGraph.getOrCreateVertex(fqn, TypeKind.ENUM);
                    }
                });
        cu.findAll(com.github.javaparser.ast.body.RecordDeclaration.class)
                .forEach(decl -> {
                    var fqn = decl.getFullyQualifiedName().orElse(null);
                    if (fqn != null) {
                        typeGraph.getOrCreateVertex(fqn, TypeKind.RECORD);
                    }
                });
        cu.findAll(com.github.javaparser.ast.body.AnnotationDeclaration.class)
                .forEach(decl -> {
                    var fqn = decl.getFullyQualifiedName().orElse(null);
                    if (fqn != null) {
                        typeGraph.getOrCreateVertex(fqn, TypeKind.ANNOTATION);
                    }
                });
    }
}
