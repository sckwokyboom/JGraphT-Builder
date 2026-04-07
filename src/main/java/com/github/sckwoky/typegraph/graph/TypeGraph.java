package com.github.sckwoky.typegraph.graph;

import com.github.sckwoky.typegraph.model.*;
import org.jgrapht.Graph;
import org.jgrapht.graph.builder.GraphTypeBuilder;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Wrapper around a JGraphT directed multigraph with typed vertices and relationships.
 * Handles vertex deduplication by fully qualified name.
 */
public class TypeGraph {

    private final Graph<TypeVertex, TypeRelationship> graph;
    private final Map<String, TypeVertex> vertexIndex = new HashMap<>();

    public TypeGraph() {
        this.graph = GraphTypeBuilder
                .<TypeVertex, TypeRelationship>directed()
                .allowingMultipleEdges(true)
                .allowingSelfLoops(true)
                .buildGraph();
    }

    /**
     * Gets or creates a vertex for the given type name.
     * If the vertex already exists, upgrades its kind from EXTERNAL if a more specific kind is provided.
     */
    public TypeVertex getOrCreateVertex(String fqn, TypeKind kind) {
        return vertexIndex.compute(fqn, (key, existing) -> {
            if (existing == null) {
                var v = new TypeVertex(fqn, kind);
                graph.addVertex(v);
                return v;
            }
            // Upgrade from EXTERNAL to a concrete kind
            if (existing.kind() == TypeKind.EXTERNAL && kind != TypeKind.EXTERNAL) {
                existing.setKind(kind);
            }
            return existing;
        });
    }

    /**
     * Adds a relationship between two vertices, creating them if needed.
     */
    public TypeRelationship addRelationship(String sourceFqn, TypeKind sourceKind,
                                             String targetFqn, TypeKind targetKind,
                                             RelationshipKind kind, MethodSignature signature) {
        var source = getOrCreateVertex(sourceFqn, sourceKind);
        var target = getOrCreateVertex(targetFqn, targetKind);
        var edge = new TypeRelationship(kind, signature);
        graph.addEdge(source, target, edge);
        return edge;
    }

    public Graph<TypeVertex, TypeRelationship> jgraphtGraph() {
        return graph;
    }

    public Optional<TypeVertex> findVertex(String fqn) {
        return Optional.ofNullable(vertexIndex.get(fqn));
    }

    public Set<TypeVertex> vertices() {
        return graph.vertexSet();
    }

    public Set<TypeRelationship> edges() {
        return graph.edgeSet();
    }

    public Set<TypeRelationship> edgesOf(RelationshipKind kind) {
        return graph.edgeSet().stream()
                .filter(e -> e.kind() == kind)
                .collect(Collectors.toSet());
    }

    public TypeVertex getEdgeSource(TypeRelationship edge) {
        return graph.getEdgeSource(edge);
    }

    public TypeVertex getEdgeTarget(TypeRelationship edge) {
        return graph.getEdgeTarget(edge);
    }

    public int vertexCount() {
        return graph.vertexSet().size();
    }

    public int edgeCount() {
        return graph.edgeSet().size();
    }

    public String summary() {
        var counts = new EnumMap<RelationshipKind, Integer>(RelationshipKind.class);
        for (var e : graph.edgeSet()) {
            counts.merge(e.kind(), 1, Integer::sum);
        }
        return "TypeGraph: %d vertices, %d edges %s".formatted(vertexCount(), edgeCount(), counts);
    }
}
