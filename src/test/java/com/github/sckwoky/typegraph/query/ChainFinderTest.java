package com.github.sckwoky.typegraph.query;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.sckwoky.typegraph.graph.TypeGraph;
import com.github.sckwoky.typegraph.graph.TypeGraphBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainFinderTest {

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
    void findsDirectChain() {
        // String → Dog via Dog constructor (String consumed by Dog, Dog produces Dog)
        var finder = new ChainFinder(graph);
        var chains = finder.findChains(List.of("java.lang.String"), "com.example.Dog");
        assertThat(chains).isNotEmpty();
        System.out.println("String → Dog chains:");
        chains.forEach(c -> System.out.println("  " + c));
    }

    @Test
    void findsMultiStepChain() {
        // String → Owner (String consumed by Owner constructor, Owner produces Owner)
        // Then Owner → Dog via adoptDog
        var finder = new ChainFinder(graph);
        var chains = finder.findChains(List.of("java.lang.String"), "com.example.Owner");
        assertThat(chains).isNotEmpty();
        System.out.println("String → Owner chains:");
        chains.forEach(c -> System.out.println("  " + c));
    }

    @Test
    void noChainForUnrelatedTypes() {
        var finder = new ChainFinder(graph);
        var chains = finder.findChains(List.of("java.lang.Boolean"), "com.example.Dog");
        assertThat(chains).isEmpty();
    }
}
