package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.sckwoky.typegraph.flow.model.ControlSubtype;
import com.github.sckwoky.typegraph.flow.model.FlowEdgeKind;
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
    void binaryExpressionProducesSubGraph() {
        // OwnerHelper#findOrAdopt contains binary ops like "retries - 1", "counter < retries",
        // "age > 0", "counter + 1" — all should produce BINARY_OP nodes with typed edges
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt");

        // There must be BINARY_OP nodes
        var binOps = graph.nodesOf(FlowNodeKind.BINARY_OP);
        assertThat(binOps).isNotEmpty();

        // Each BINARY_OP must have the "operator" attribute
        assertThat(binOps).allSatisfy(node ->
                assertThat(node.attr("operator")).isNotNull());

        // Each BINARY_OP must have at least one incoming LEFT_OPERAND or RIGHT_OPERAND edge
        for (var binOp : binOps) {
            var inEdgeKinds = graph.incomingEdgesOf(binOp).stream()
                    .map(e -> e.kind())
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(inEdgeKinds)
                    .as("BINARY_OP %s should have LEFT_OPERAND or RIGHT_OPERAND edges", binOp.id())
                    .containsAnyOf(FlowEdgeKind.LEFT_OPERAND, FlowEdgeKind.RIGHT_OPERAND);
        }
    }

    @Test
    void literalNodesHaveTypedAttributes() {
        // OwnerHelper#findOrAdopt contains literal "0" (counter), "1" (retries - 1, counter + 1)
        // OwnerHelper#lookup throws new RuntimeException("not found") — has string literal
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt");
        var literals = graph.nodesOf(FlowNodeKind.LITERAL);
        assertThat(literals).isNotEmpty();
        assertThat(literals).allSatisfy(node -> {
            assertThat(node.attr("literalType")).isNotNull();
            assertThat(node.attr("value")).isNotNull();
        });
    }

    @Test
    void ternaryProducesTypedNode() {
        // OwnerHelper#describe: dog == null ? "none" : dog.name()
        var graph = findMethod("com.example.OwnerHelper", "describe");
        var ternaries = graph.nodesOf(FlowNodeKind.TERNARY);
        assertThat(ternaries).isNotEmpty();
        // Ternary must have a TERNARY_CONDITION incoming edge
        for (var tern : ternaries) {
            var inEdgeKinds = graph.incomingEdgesOf(tern).stream()
                    .map(e -> e.kind())
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(inEdgeKinds).contains(FlowEdgeKind.TERNARY_CONDITION);
        }
    }

    @Test
    void methodCallUsesReceiverEdge() {
        // Owner#adoptDog: dog.setOwner(this), dogs.add(dog) — both use RECEIVER edge
        var graph = findMethod("com.example.Owner", "adoptDog");
        long receiverEdges = graph.edges().stream()
                .filter(e -> e.kind() == FlowEdgeKind.RECEIVER)
                .count();
        assertThat(receiverEdges).isGreaterThanOrEqualTo(2);
    }

    @Test
    void argPassLabelIsNumeric() {
        // Args on CALL nodes should use plain numeric labels "0", "1", ...
        var graph = findMethod("com.example.Owner", "adoptDog");
        var argEdges = graph.edges().stream()
                .filter(e -> e.kind() == FlowEdgeKind.ARG_PASS)
                .collect(java.util.stream.Collectors.toList());
        assertThat(argEdges).isNotEmpty();
        assertThat(argEdges).allSatisfy(e ->
                assertThat(e.label()).matches("\\d+"));
    }

    @Test
    void chainedCallProducesNestedCallResults() {
        // Owner#adoptDog calls new Dog(...), dog.setOwner(this), dogs.add(dog)
        var graph = findMethod("com.example.Owner", "adoptDog");
        // CALL covers method invocations; OBJECT_CREATE covers constructor calls (new Dog(...))
        long invocations = graph.nodes().stream()
                .filter(n -> n.kind() == FlowNodeKind.CALL || n.kind() == FlowNodeKind.OBJECT_CREATE)
                .count();
        assertThat(invocations).isGreaterThanOrEqualTo(3);
    }

    @Test
    void branchNodesHaveConditionEdge() {
        var graph = findMethod("com.example.OwnerHelper", "findOrAdopt");
        for (var branch : graph.branchNodes()) {
            if (branch.controlSubtype() == ControlSubtype.IF) {
                var condEdges = graph.incomingEdgesOf(branch).stream()
                        .filter(e -> e.kind() == FlowEdgeKind.CONDITION)
                        .toList();
                assertThat(condEdges).as("IF branch should have CONDITION edge: " + branch).isNotEmpty();
            }
        }
    }
}
