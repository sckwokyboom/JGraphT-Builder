package com.github.sckwoky.typegraph.compose;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.sckwoky.typegraph.compose.model.*;
import com.github.sckwoky.typegraph.graph.TypeGraph;
import com.github.sckwoky.typegraph.graph.TypeGraphBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainSearchEngineTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");
    private static TypeGraph typeGraph;
    private static GlobalCompositionGraph compositionGraph;
    private static SignatureMatcher matcher;

    @BeforeAll
    static void setUp() {
        var typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(FIXTURES));
        var config = new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver));

        typeGraph = TypeGraphBuilder.buildFromSourceRoots(List.of(FIXTURES), config);
        compositionGraph = new CompositionGraphBuilder().build(typeGraph);
        matcher = new SignatureMatcher(typeGraph);
    }

    @Test
    void findsDogConstructorAsChainForStringIntTarget() {
        // Synthetic target: a hypothetical method that takes (String, int) and returns Dog.
        // Should find at minimum the Dog constructor as a 1-step chain.
        var target = new TargetMethodSpec(
                "com.example.OwnerService",          // synthetic
                "createDog",
                List.of("java.lang.String", "java.lang.Integer"),
                List.of("name", "age"),
                List.of(),                            // no fields
                "com.example.Dog",
                false
        );

        var engine = new ChainSearchEngine(matcher);
        var raw = engine.search(target, compositionGraph, ChainSearchEngine.SearchOptions.defaults());

        assertThat(raw).isNotEmpty();
        // At least one chain should call Dog::<init> as final step
        boolean hasDogCtor = raw.stream().anyMatch(c ->
                c.steps().stream().anyMatch(s ->
                        s.operator().isConstructor() &&
                        s.operator().declaringType().equals("com.example.Dog")));
        assertThat(hasDogCtor).as("at least one chain ends in Dog constructor").isTrue();
    }

    @Test
    void respectsTopK() {
        var target = new TargetMethodSpec(
                "com.example.OwnerService", "anything",
                List.of("java.lang.String"),
                List.of("name"),
                List.of(),
                "com.example.Owner",
                false
        );
        var engine = new ChainSearchEngine(matcher);
        var opts = new ChainSearchEngine.SearchOptions(4, 3, SubtypingPolicy.ALLOW_SUBTYPE, true);
        var raw = engine.search(target, compositionGraph, opts);
        assertThat(raw.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void instanceMethodWithoutReceiverIsNotProposed() {
        // Target only has a String, no Owner instance.
        // Should NOT propose Owner.adoptDog as it requires Owner receiver.
        var target = new TargetMethodSpec(
                "com.example.UnrelatedClass", "build",
                List.of("java.lang.String"),
                List.of("input"),
                List.of(),
                "com.example.Dog",
                true   // static — no this
        );
        var engine = new ChainSearchEngine(matcher);
        var raw = engine.search(target, compositionGraph, ChainSearchEngine.SearchOptions.defaults());

        // None of the chains should use Owner.adoptDog as a step
        boolean usesAdoptDog = raw.stream().anyMatch(c ->
                c.steps().stream().anyMatch(s ->
                        s.operator().signature().methodName().equals("adoptDog") &&
                        !s.operator().isConstructor()));
        assertThat(usesAdoptDog).as("no chain should use instance Owner.adoptDog without an Owner").isFalse();
    }
}
