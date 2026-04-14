package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.sckwoky.typegraph.flow.model.ControlSubtype;
import com.github.sckwoky.typegraph.flow.model.FlowNodeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MethodFlowBuilderTest {

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
    void findOrAdoptHasAllControlStructures() {
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt");

        // Should have at least: try (BRANCH/TRY), 2 if (BRANCH/IF), foreach (LOOP/FOREACH),
        // while (LOOP/WHILE), finally (BRANCH/FINALLY), and corresponding MERGE nodes
        var branchSubtypes = graph.branchNodes().stream()
                .map(n -> n.controlSubtype())
                .collect(Collectors.toSet());
        assertThat(branchSubtypes).contains(ControlSubtype.IF, ControlSubtype.TRY);

        var loopSubtypes = graph.loopNodes().stream()
                .map(n -> n.controlSubtype())
                .collect(Collectors.toSet());
        assertThat(loopSubtypes).contains(ControlSubtype.FOREACH, ControlSubtype.WHILE);
    }

    @Test
    void findOrAdoptHasMultipleReturns() {
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt");
        assertThat(graph.returnNodes()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void backwardSlicerProducesSlicePerReturn() {
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt");
        var slicer = new BackwardSlicer();
        var slices = slicer.slicePerReturn(graph);
        assertThat(slices).hasSizeGreaterThanOrEqualTo(2);
        for (var slice : slices.values()) {
            assertThat(slice).isNotEmpty();
            // Slice contains the RETURN node it started from
            boolean hasReturn = slice.stream().anyMatch(n -> n.kind() == FlowNodeKind.RETURN);
            assertThat(hasReturn).isTrue();
        }
    }

    @Test
    void mergeValueProducedAfterIfElseBranches() {
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt");
        // result is reassigned in try, catch, then in if (== null), else if; expect MERGE_VALUE nodes
        long phiCount = graph.nodes().stream()
                .filter(n -> n.kind() == FlowNodeKind.MERGE_VALUE)
                .count();
        assertThat(phiCount).isGreaterThan(0);
    }

    @Test
    void chainedCallProducesNestedCallResults() {
        // Owner#adoptDog calls new Dog(...), dog.setOwner(this), dogs.add(dog)
        var graph = findMethod("com.example.Owner", "adoptDog");
        var calls = graph.callNodes();
        assertThat(calls.size()).isGreaterThanOrEqualTo(3);
    }
}
