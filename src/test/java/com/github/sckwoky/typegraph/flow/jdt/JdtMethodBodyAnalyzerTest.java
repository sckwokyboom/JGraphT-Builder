package com.github.sckwoky.typegraph.flow.jdt;

import com.github.sckwoky.typegraph.flow.FlowGraphService;
import com.github.sckwoky.typegraph.flow.MethodFlowGraph;
import com.github.sckwoky.typegraph.flow.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JdtMethodBodyAnalyzer}, verifying that the JDT-based
 * flow graph builder produces correct graphs from the same fixtures used
 * by the JavaParser-based tests.
 */
class JdtMethodBodyAnalyzerTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private static List<FlowGraphService.Entry> entries;

    @BeforeAll
    static void setUp() {
        var jdtEnv   = new JdtEnvironment(List.of(FIXTURES), List.of());
        var indexer   = new JdtSourceIndexer(jdtEnv);
        var analyzer  = new JdtMethodBodyAnalyzer(jdtEnv);
        var service   = new FlowGraphService(indexer, analyzer);
        entries = service.buildAll(List.of(FIXTURES), t -> true);
    }

    // ─── Basic sanity ──────────────────────────────────────────────────

    @Test
    void allMethodsProduceNonEmptyGraphs() {
        assertThat(entries).isNotEmpty();
        for (var entry : entries) {
            assertThat(entry.graph().nodeCount())
                    .as("Graph for %s should have nodes", entry.displayName())
                    .isGreaterThan(0);
        }
    }

    @Test
    void noNullGraphs() {
        assertThat(entries)
                .extracting(FlowGraphService.Entry::graph)
                .doesNotContainNull();
    }

    @Test
    void mostGraphsHaveEdges() {
        // Methods with bodies beyond trivial void-return should produce edges.
        // voidReturn() is special: just "return;" with no expression, so 0 edges is valid.
        long withEdges = entries.stream()
                .filter(e -> e.graph().edgeCount() > 0)
                .count();
        assertThat(withEdges)
                .as("Most graphs should have edges")
                .isGreaterThan(entries.size() / 2);
    }

    // ─── findOrAdopt structural checks ─────────────────────────────────

    @Test
    void findOrAdoptHasBranchAndLoopNodes() {
        var graph = graphFor("com.example.OwnerHelper", "findOrAdopt");
        assertThat(graph).isNotNull();

        assertThat(graph.branchNodes())
                .as("findOrAdopt should contain BRANCH nodes (if/try/switch)")
                .isNotEmpty();
        assertThat(graph.loopNodes())
                .as("findOrAdopt should contain LOOP nodes (foreach/while)")
                .isNotEmpty();
    }

    @Test
    void findOrAdoptHasMultipleReturnNodes() {
        var graph = graphFor("com.example.OwnerHelper", "findOrAdopt");
        assertThat(graph).isNotNull();

        assertThat(graph.returnNodes())
                .as("findOrAdopt should have multiple RETURN nodes")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void findOrAdoptHasParams() {
        var graph = graphFor("com.example.OwnerHelper", "findOrAdopt");
        assertThat(graph).isNotNull();

        assertThat(graph.paramNodes())
                .as("findOrAdopt has parameters 'name' and 'age'")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    // ─── Expression-level checks ───────────────────────────────────────

    @Test
    void binaryExpressionsHaveLeftAndRightOperandEdges() {
        var graph = graphFor("com.example.OwnerHelper", "findOrAdopt");
        assertThat(graph).isNotNull();

        var binaryOps = graph.nodesOf(FlowNodeKind.BINARY_OP);
        assertThat(binaryOps).as("findOrAdopt should have binary ops").isNotEmpty();

        // At least one binary op should have both LEFT_OPERAND and RIGHT_OPERAND edges
        boolean foundBoth = binaryOps.stream().anyMatch(binop -> {
            var inEdges = graph.incomingEdgesOf(binop);
            boolean hasLeft = inEdges.stream().anyMatch(e -> e.kind() == FlowEdgeKind.LEFT_OPERAND);
            boolean hasRight = inEdges.stream().anyMatch(e -> e.kind() == FlowEdgeKind.RIGHT_OPERAND);
            return hasLeft && hasRight;
        });
        assertThat(foundBoth)
                .as("At least one BINARY_OP should have both LEFT_OPERAND and RIGHT_OPERAND edges")
                .isTrue();
    }

    @Test
    void methodCallsHaveReceiverEdges() {
        var graph = graphFor("com.example.OwnerHelper", "findOrAdopt");
        assertThat(graph).isNotNull();

        var calls = graph.callNodes();
        assertThat(calls).as("findOrAdopt should have CALL nodes").isNotEmpty();

        // At least one call should have a RECEIVER edge
        boolean hasReceiver = calls.stream().anyMatch(call -> {
            var inEdges = graph.incomingEdgesOf(call);
            return inEdges.stream().anyMatch(e -> e.kind() == FlowEdgeKind.RECEIVER);
        });
        assertThat(hasReceiver)
                .as("At least one CALL should have a RECEIVER edge")
                .isTrue();
    }

    @Test
    void ifBranchesHaveConditionEdges() {
        var graph = graphFor("com.example.OwnerHelper", "findOrAdopt");
        assertThat(graph).isNotNull();

        var branches = graph.branchNodes().stream()
                .filter(b -> b.controlSubtype() == ControlSubtype.IF)
                .toList();
        assertThat(branches).as("findOrAdopt should have IF branches").isNotEmpty();

        // Every IF branch should have a CONDITION edge
        for (var branch : branches) {
            var inEdges = graph.incomingEdgesOf(branch);
            boolean hasCondition = inEdges.stream()
                    .anyMatch(e -> e.kind() == FlowEdgeKind.CONDITION);
            assertThat(hasCondition)
                    .as("IF branch %s should have a CONDITION edge", branch.id())
                    .isTrue();
        }
    }

    // ─── Loop-specific checks ──────────────────────────────────────────

    @Test
    void foreachLoopHasIterableEdge() {
        var graph = graphFor("com.example.OwnerHelper", "findOrAdopt");
        assertThat(graph).isNotNull();

        var foreachLoops = graph.loopNodes().stream()
                .filter(l -> l.controlSubtype() == ControlSubtype.FOREACH)
                .toList();
        assertThat(foreachLoops).as("findOrAdopt should have FOREACH loops").isNotEmpty();

        for (var loop : foreachLoops) {
            var inEdges = graph.incomingEdgesOf(loop);
            boolean hasIterable = inEdges.stream()
                    .anyMatch(e -> e.kind() == FlowEdgeKind.LOOP_ITERABLE);
            assertThat(hasIterable)
                    .as("FOREACH loop %s should have a LOOP_ITERABLE edge", loop.id())
                    .isTrue();
        }
    }

    @Test
    void whileLoopHasConditionEdge() {
        var graph = graphFor("com.example.OwnerHelper", "findOrAdopt");
        assertThat(graph).isNotNull();

        var whileLoops = graph.loopNodes().stream()
                .filter(l -> l.controlSubtype() == ControlSubtype.WHILE)
                .toList();
        assertThat(whileLoops).as("findOrAdopt should have WHILE loops").isNotEmpty();

        for (var loop : whileLoops) {
            var inEdges = graph.incomingEdgesOf(loop);
            boolean hasCondition = inEdges.stream()
                    .anyMatch(e -> e.kind() == FlowEdgeKind.CONDITION);
            assertThat(hasCondition)
                    .as("WHILE loop %s should have a CONDITION edge", loop.id())
                    .isTrue();
        }
    }

    // ─── Try/catch checks ──────────────────────────────────────────────

    @Test
    void findOrAdoptHasTryAndCatchBranches() {
        var graph = graphFor("com.example.OwnerHelper", "findOrAdopt");
        assertThat(graph).isNotNull();

        var tryBranches = graph.branchNodes().stream()
                .filter(b -> b.controlSubtype() == ControlSubtype.TRY)
                .toList();
        assertThat(tryBranches)
                .as("findOrAdopt should have a TRY branch")
                .isNotEmpty();

        var catchBranches = graph.branchNodes().stream()
                .filter(b -> b.controlSubtype() == ControlSubtype.CATCH)
                .toList();
        assertThat(catchBranches)
                .as("findOrAdopt should have a CATCH branch")
                .isNotEmpty();
    }

    // ─── Ternary in describe() ─────────────────────────────────────────

    @Test
    void describeHasTernaryNode() {
        var graph = graphFor("com.example.OwnerHelper", "describe");
        assertThat(graph).isNotNull();

        var ternaryNodes = graph.nodesOf(FlowNodeKind.TERNARY);
        assertThat(ternaryNodes)
                .as("describe() should contain a TERNARY node")
                .isNotEmpty();

        // Verify ternary structure: TERNARY_CONDITION, TERNARY_THEN, TERNARY_ELSE edges
        for (var tern : ternaryNodes) {
            var inEdges = graph.incomingEdgesOf(tern);
            var edgeKinds = inEdges.stream().map(FlowEdge::kind).collect(Collectors.toSet());
            assertThat(edgeKinds).contains(FlowEdgeKind.TERNARY_CONDITION);
            assertThat(edgeKinds).contains(FlowEdgeKind.TERNARY_THEN);
            assertThat(edgeKinds).contains(FlowEdgeKind.TERNARY_ELSE);
        }
    }

    // ─── Constructor analysis ──────────────────────────────────────────

    @Test
    void constructorProducesGraph() {
        var graph = graphFor("com.example.OwnerHelper", "<init>");
        assertThat(graph).isNotNull();
        assertThat(graph.nodeCount()).isGreaterThan(0);

        // Constructor should have a THIS_REF node
        assertThat(graph.nodesOf(FlowNodeKind.THIS_REF))
                .as("Constructor should have a THIS_REF node")
                .isNotEmpty();
    }

    // ─── Object creation (new) ─────────────────────────────────────────

    @Test
    void adoptDogHasObjectCreation() {
        var graph = graphFor("com.example.Owner", "adoptDog");
        assertThat(graph).isNotNull();

        var objectCreations = graph.nodesOf(FlowNodeKind.OBJECT_CREATE);
        assertThat(objectCreations)
                .as("adoptDog should have OBJECT_CREATE for 'new Dog(...)'")
                .isNotEmpty();
    }

    // ─── Throw statement ───────────────────────────────────────────────

    @Test
    void lookupHasThrowNode() {
        var graph = graphFor("com.example.OwnerHelper", "lookup");
        assertThat(graph).isNotNull();

        var throwNodes = graph.nodesOf(FlowNodeKind.THROW);
        assertThat(throwNodes)
                .as("lookup should have a THROW node")
                .isNotEmpty();

        // Verify THROW_VALUE edge
        for (var throwNode : throwNodes) {
            var inEdges = graph.incomingEdgesOf(throwNode);
            boolean hasThrowValue = inEdges.stream()
                    .anyMatch(e -> e.kind() == FlowEdgeKind.THROW_VALUE);
            assertThat(hasThrowValue)
                    .as("THROW node should have a THROW_VALUE edge")
                    .isTrue();
        }
    }

    // ─── Cross-class coverage ──────────────────────────────────────────

    @Test
    void allFixtureClassesHaveEntries() {
        var types = entries.stream()
                .map(FlowGraphService.Entry::declaringType)
                .collect(Collectors.toSet());

        assertThat(types).contains(
                "com.example.OwnerHelper",
                "com.example.Owner",
                "com.example.Dog"
        );
    }

    // ─── Helper ────────────────────────────────────────────────────────

    private MethodFlowGraph graphFor(String declaringType, String methodName) {
        return entries.stream()
                .filter(e -> e.declaringType().equals(declaringType)
                        && e.methodName().equals(methodName))
                .map(FlowGraphService.Entry::graph)
                .findFirst()
                .orElse(null);
    }
}
