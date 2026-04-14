package com.github.sckwoky.typegraph.flow.model;

import org.jgrapht.graph.DefaultEdge;

/**
 * An edge in a {@link com.github.sckwoky.typegraph.flow.MethodFlowGraph}.
 * Object identity is used (multigraph), so multiple edges may exist between
 * the same pair of nodes (e.g. several arguments of one CALL).
 */
public class FlowEdge extends DefaultEdge {

    private final FlowEdgeKind kind;
    private final String label;

    public FlowEdge(FlowEdgeKind kind, String label) {
        this.kind = kind;
        this.label = label == null ? "" : label;
    }

    public FlowEdge(FlowEdgeKind kind) {
        this(kind, "");
    }

    public FlowEdgeKind kind() { return kind; }
    public String label() { return label; }

    @Override
    public String toString() {
        return kind.name() + (label.isEmpty() ? "" : "[" + label + "]");
    }
}
