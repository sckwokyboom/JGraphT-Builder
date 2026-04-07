package com.github.sckwoky.typegraph.model;

import org.jgrapht.graph.DefaultEdge;

/**
 * An edge in the type graph representing a relationship between two types.
 * <p>
 * Does NOT override equals/hashCode — JGraphT's multigraph relies on
 * object identity to allow multiple edges between the same vertex pair.
 */
public class TypeRelationship extends DefaultEdge {

    private final RelationshipKind kind;
    private final MethodSignature signature;

    public TypeRelationship(RelationshipKind kind, MethodSignature signature) {
        this.kind = kind;
        this.signature = signature;
    }

    public TypeRelationship(RelationshipKind kind) {
        this(kind, null);
    }

    public RelationshipKind kind() {
        return kind;
    }

    /** Non-null for CONSUMES and PRODUCES edges; null for IS and HAS. */
    public MethodSignature signature() {
        return signature;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder(kind.name());
        if (signature != null) {
            sb.append(" via ").append(signature);
        }
        return sb.toString();
    }
}
