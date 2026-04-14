package com.github.sckwoky.typegraph.compose.model;

import org.jgrapht.graph.DefaultEdge;

/**
 * An edge in the {@link com.github.sckwoky.typegraph.compose.GlobalCompositionGraph}.
 * Object identity is used for edge dedup (multigraph).
 */
public class CompositionEdge extends DefaultEdge {

    private final CompositionEdgeKind kind;
    private final int slotIndex;       // for CONSUMES: param slot index; -1 otherwise
    private final int weight;          // for EVIDENCE_CALLS: occurrence count

    public CompositionEdge(CompositionEdgeKind kind, int slotIndex, int weight) {
        this.kind = kind;
        this.slotIndex = slotIndex;
        this.weight = weight;
    }

    public CompositionEdge(CompositionEdgeKind kind) {
        this(kind, -1, 1);
    }

    public CompositionEdgeKind kind() { return kind; }
    public int slotIndex() { return slotIndex; }
    public int weight() { return weight; }

    @Override
    public String toString() {
        return kind.name() + (slotIndex >= 0 ? "[" + slotIndex + "]" : "");
    }
}
