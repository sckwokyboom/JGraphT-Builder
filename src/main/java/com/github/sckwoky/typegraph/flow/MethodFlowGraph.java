package com.github.sckwoky.typegraph.flow;

import com.github.sckwoky.typegraph.flow.model.*;
import com.github.sckwoky.typegraph.model.MethodSignature;
import org.jgrapht.Graph;
import org.jgrapht.graph.builder.GraphTypeBuilder;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Wraps a JGraphT directed multigraph of {@link FlowNode} vertices and
 * {@link FlowEdge} edges representing one method body.
 */
public class MethodFlowGraph {

    private final Graph<FlowNode, FlowEdge> graph;
    private final MethodSignature methodSignature;
    private int idCounter = 0;

    public MethodFlowGraph(MethodSignature methodSignature) {
        this.methodSignature = methodSignature;
        this.graph = GraphTypeBuilder
                .<FlowNode, FlowEdge>directed()
                .allowingMultipleEdges(true)
                .allowingSelfLoops(true)
                .buildGraph();
    }

    public MethodSignature methodSignature() {
        return methodSignature;
    }

    public Graph<FlowNode, FlowEdge> jgraphtGraph() {
        return graph;
    }

    public String nextId(String prefix) {
        return prefix + "_" + (idCounter++);
    }

    public FlowNode addNode(FlowNode node) {
        graph.addVertex(node);
        return node;
    }

    public FlowEdge addEdge(FlowNode src, FlowNode tgt, FlowEdgeKind kind, String label) {
        var edge = new FlowEdge(kind, label);
        graph.addEdge(src, tgt, edge);
        return edge;
    }

    public FlowEdge addEdge(FlowNode src, FlowNode tgt, FlowEdgeKind kind) {
        return addEdge(src, tgt, kind, "");
    }

    public Set<FlowNode> nodes() { return graph.vertexSet(); }
    public Set<FlowEdge> edges() { return graph.edgeSet(); }
    public int nodeCount() { return graph.vertexSet().size(); }
    public int edgeCount() { return graph.edgeSet().size(); }

    public List<FlowNode> nodesOf(FlowNodeKind kind) {
        return graph.vertexSet().stream()
                .filter(n -> n.kind() == kind)
                .collect(Collectors.toList());
    }

    public List<FlowNode> returnNodes() { return nodesOf(FlowNodeKind.RETURN); }
    public List<FlowNode> paramNodes() { return nodesOf(FlowNodeKind.PARAM); }
    public List<FlowNode> callNodes() { return nodesOf(FlowNodeKind.CALL); }
    public List<FlowNode> branchNodes() { return nodesOf(FlowNodeKind.BRANCH); }
    public List<FlowNode> loopNodes() { return nodesOf(FlowNodeKind.LOOP); }

    public Set<FlowNode> predecessorsOf(FlowNode node) {
        return graph.incomingEdgesOf(node).stream()
                .map(graph::getEdgeSource)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<FlowEdge> incomingEdgesOf(FlowNode node) {
        return graph.incomingEdgesOf(node);
    }

    public Set<FlowEdge> outgoingEdgesOf(FlowNode node) {
        return graph.outgoingEdgesOf(node);
    }

    public FlowNode getEdgeSource(FlowEdge edge) { return graph.getEdgeSource(edge); }
    public FlowNode getEdgeTarget(FlowEdge edge) { return graph.getEdgeTarget(edge); }

    public Map<FlowNodeKind, Integer> kindCounts() {
        var m = new EnumMap<FlowNodeKind, Integer>(FlowNodeKind.class);
        for (var n : graph.vertexSet()) m.merge(n.kind(), 1, Integer::sum);
        return m;
    }

    public String summary() {
        return "MethodFlowGraph[" + methodSignature + "]: " +
                nodeCount() + " nodes, " + edgeCount() + " edges " + kindCounts();
    }
}
