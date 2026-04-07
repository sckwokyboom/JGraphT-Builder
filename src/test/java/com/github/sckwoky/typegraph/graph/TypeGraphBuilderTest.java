package com.github.sckwoky.typegraph.graph;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.sckwoky.typegraph.model.RelationshipKind;
import com.github.sckwoky.typegraph.model.TypeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TypeGraphBuilderTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");
    private static TypeGraph graph;

    @BeforeAll
    static void buildGraph() {
        var typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(FIXTURES));

        var config = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));

        graph = TypeGraphBuilder.buildFromSourceRoots(List.of(FIXTURES), config);
    }

    @Test
    void graphContainsProjectTypes() {
        assertThat(graph.findVertex("com.example.Animal")).isPresent();
        assertThat(graph.findVertex("com.example.Dog")).isPresent();
        assertThat(graph.findVertex("com.example.Owner")).isPresent();
    }

    @Test
    void vertexKindsUpgraded() {
        assertThat(graph.findVertex("com.example.Animal").get().kind()).isEqualTo(TypeKind.INTERFACE);
        assertThat(graph.findVertex("com.example.Dog").get().kind()).isEqualTo(TypeKind.CLASS);
        assertThat(graph.findVertex("com.example.Owner").get().kind()).isEqualTo(TypeKind.CLASS);
    }

    @Test
    void hasAllEdgeKinds() {
        assertThat(graph.edgesOf(RelationshipKind.IS)).isNotEmpty();
        assertThat(graph.edgesOf(RelationshipKind.HAS)).isNotEmpty();
        assertThat(graph.edgesOf(RelationshipKind.CONSUMES)).isNotEmpty();
        assertThat(graph.edgesOf(RelationshipKind.PRODUCES)).isNotEmpty();
    }

    @Test
    void summaryContainsInfo() {
        var summary = graph.summary();
        assertThat(summary).contains("TypeGraph:");
        assertThat(summary).contains("vertices");
        assertThat(summary).contains("edges");
    }
}
