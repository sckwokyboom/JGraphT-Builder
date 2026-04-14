package com.github.sckwoky.typegraph.compose;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.sckwoky.typegraph.compose.model.CompositionNode;
import com.github.sckwoky.typegraph.graph.TypeGraph;
import com.github.sckwoky.typegraph.graph.TypeGraphBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositionGraphBuilderTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");
    private static GlobalCompositionGraph graph;

    @BeforeAll
    static void setUp() {
        var typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(FIXTURES));
        var config = new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver));
        TypeGraph typeGraph = TypeGraphBuilder.buildFromSourceRoots(List.of(FIXTURES), config);
        graph = new CompositionGraphBuilder().build(typeGraph);
    }

    @Test
    void containsDogConstructor() {
        var dogProducers = graph.methodsProducing("com.example.Dog");
        assertThat(dogProducers).isNotEmpty();
        boolean hasCtor = dogProducers.stream().anyMatch(m -> m.operator().isConstructor());
        assertThat(hasCtor).isTrue();
    }

    @Test
    void instanceMethodHasReceiverSlot() {
        var dogProducers = graph.methodsProducing("com.example.Dog");
        var adoptDog = dogProducers.stream()
                .filter(m -> m.operator().signature().methodName().equals("adoptDog"))
                .findFirst();
        assertThat(adoptDog).isPresent();
        assertThat(adoptDog.get().operator().hasReceiver()).isTrue();
        assertThat(adoptDog.get().operator().receiverSlot().typeFqn()).isEqualTo("com.example.Owner");
    }

    @Test
    void constructorHasNoReceiverSlot() {
        var dogProducers = graph.methodsProducing("com.example.Dog");
        var ctor = dogProducers.stream()
                .filter(m -> m.operator().isConstructor())
                .findFirst()
                .orElseThrow();
        assertThat(ctor.operator().hasReceiver()).isFalse();
        assertThat(ctor.operator().paramSlots()).hasSize(2);
    }
}
