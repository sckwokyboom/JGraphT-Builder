package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.*;
import com.github.sckwoky.typegraph.compose.model.CompositionNode.FieldNode;
import com.github.sckwoky.typegraph.compose.model.CompositionNode.MethodNode;
import com.github.sckwoky.typegraph.compose.model.CompositionNode.TypeNode;
import org.jgrapht.Graph;
import org.jgrapht.graph.builder.GraphTypeBuilder;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Wrapper around a JGraphT directed multigraph of {@link CompositionNode} vertices
 * and {@link CompositionEdge} edges. Maintains indices for fast lookup of methods
 * by produced type and by parameter type.
 */
public class GlobalCompositionGraph {

    private final Graph<CompositionNode, CompositionEdge> graph;
    private final Map<String, CompositionNode> nodeIndex = new HashMap<>();   // id → node
    private final Map<String, List<MethodNode>> methodsByProducedType = new HashMap<>();
    private final Map<String, List<MethodNode>> methodsByParamType = new HashMap<>();
    private final Map<String, List<MethodNode>> methodsByDeclaringType = new HashMap<>();
    private final Map<String, List<FieldNode>> fieldsByDeclaringType = new HashMap<>();

    public GlobalCompositionGraph() {
        this.graph = GraphTypeBuilder
                .<CompositionNode, CompositionEdge>directed()
                .allowingMultipleEdges(true)
                .allowingSelfLoops(true)
                .buildGraph();
    }

    public Graph<CompositionNode, CompositionEdge> jgraphtGraph() {
        return graph;
    }

    public TypeNode getOrCreateType(String fqn) {
        var node = (TypeNode) nodeIndex.computeIfAbsent("type:" + fqn, k -> {
            var n = new TypeNode(fqn);
            graph.addVertex(n);
            return n;
        });
        return node;
    }

    public MethodNode addMethod(MethodOperator op) {
        var id = "method:" + op.signature();
        var existing = nodeIndex.get(id);
        if (existing instanceof MethodNode mn) return mn;

        var node = new MethodNode(op);
        nodeIndex.put(id, node);
        graph.addVertex(node);

        methodsByProducedType.computeIfAbsent(op.returnType(), k -> new ArrayList<>()).add(node);
        methodsByDeclaringType.computeIfAbsent(op.declaringType(), k -> new ArrayList<>()).add(node);
        for (var slot : op.paramSlots()) {
            methodsByParamType.computeIfAbsent(slot.typeFqn(), k -> new ArrayList<>()).add(node);
        }
        return node;
    }

    public FieldNode addField(String declaringType, String fieldName, String fieldType) {
        var id = "field:" + declaringType + "#" + fieldName;
        var existing = nodeIndex.get(id);
        if (existing instanceof FieldNode fn) return fn;

        var node = new FieldNode(declaringType, fieldName, fieldType);
        nodeIndex.put(id, node);
        graph.addVertex(node);
        fieldsByDeclaringType.computeIfAbsent(declaringType, k -> new ArrayList<>()).add(node);
        return node;
    }

    public CompositionEdge addEdge(CompositionNode source, CompositionNode target, CompositionEdge edge) {
        graph.addEdge(source, target, edge);
        return edge;
    }

    public List<MethodNode> methodsProducing(String typeFqn) {
        return methodsByProducedType.getOrDefault(typeFqn, List.of());
    }

    public List<MethodNode> methodsByDeclaringType(String typeFqn) {
        return methodsByDeclaringType.getOrDefault(typeFqn, List.of());
    }

    public List<FieldNode> fieldsOf(String declaringType) {
        return fieldsByDeclaringType.getOrDefault(declaringType, List.of());
    }

    public Optional<MethodNode> findMethodById(String methodId) {
        var node = nodeIndex.get("method:" + methodId);
        return node instanceof MethodNode mn ? Optional.of(mn) : Optional.empty();
    }

    public Set<MethodNode> allMethods() {
        return graph.vertexSet().stream()
                .filter(n -> n instanceof MethodNode)
                .map(n -> (MethodNode) n)
                .collect(Collectors.toSet());
    }

    public int vertexCount() { return graph.vertexSet().size(); }
    public int edgeCount() { return graph.edgeSet().size(); }

    public String summary() {
        var counts = new EnumMap<CompositionEdgeKind, Integer>(CompositionEdgeKind.class);
        for (var e : graph.edgeSet()) counts.merge(e.kind(), 1, Integer::sum);
        return "GlobalCompositionGraph: %d nodes, %d edges %s".formatted(vertexCount(), edgeCount(), counts);
    }
}
