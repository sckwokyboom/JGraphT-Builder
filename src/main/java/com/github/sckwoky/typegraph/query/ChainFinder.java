package com.github.sckwoky.typegraph.query;

import com.github.sckwoky.typegraph.graph.TypeGraph;
import com.github.sckwoky.typegraph.model.*;
import org.jgrapht.alg.shortestpath.AllDirectedPaths;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;

import java.util.*;

/**
 * Finds chains of method calls connecting input types to an output type.
 * <p>
 * Algorithm:
 * <ol>
 *   <li>Builds a derived "method-call graph" from CONSUMES + PRODUCES edges:
 *       if a method {@code C.foo(A): B} exists, creates a derived edge A → B via C.foo</li>
 *   <li>Uses JGraphT's AllDirectedPaths to find paths from input types to output type</li>
 *   <li>Translates each path back to a MethodChain</li>
 * </ol>
 */
public class ChainFinder {

    private final TypeGraph typeGraph;
    private final int maxDepth;

    /**
     * A derived edge linking an input type to an output type via a method.
     */
    static class DerivedEdge extends DefaultEdge {
        final MethodSignature method;

        DerivedEdge(MethodSignature method) {
            this.method = method;
        }
    }

    public ChainFinder(TypeGraph typeGraph, int maxDepth) {
        this.typeGraph = typeGraph;
        this.maxDepth = maxDepth;
    }

    public ChainFinder(TypeGraph typeGraph) {
        this(typeGraph, 5);
    }

    /**
     * Finds method chains that can transform the given input types into the output type.
     *
     * @param inputTypes  fully qualified names of the input parameter types
     * @param outputType  fully qualified name of the desired output type
     * @return list of method chains, ordered shortest-first
     */
    public List<MethodChain> findChains(List<String> inputTypes, String outputType) {
        var derivedGraph = buildDerivedGraph();

        // Collect vertices
        var inputVertices = new HashSet<String>();
        for (var input : inputTypes) {
            var normalized = TypeNormalizer.normalize(input);
            if (normalized != null && derivedGraph.containsVertex(normalized)) {
                inputVertices.add(normalized);
            }
        }
        var normalizedOutput = TypeNormalizer.normalize(outputType);
        if (normalizedOutput == null || !derivedGraph.containsVertex(normalizedOutput)) {
            return List.of();
        }

        var pathFinder = new AllDirectedPaths<>(derivedGraph);
        var chains = new ArrayList<MethodChain>();

        for (var inputVertex : inputVertices) {
            if (inputVertex.equals(normalizedOutput)) continue;

            var paths = pathFinder.getAllPaths(
                    inputVertex, normalizedOutput, true, maxDepth);

            for (var path : paths) {
                var steps = new ArrayList<MethodSignature>();
                for (var edge : path.getEdgeList()) {
                    steps.add(edge.method);
                }
                if (!steps.isEmpty()) {
                    chains.add(new MethodChain(steps, inputTypes, outputType));
                }
            }
        }

        // Sort by chain length (shortest first)
        chains.sort(Comparator.comparingInt(c -> c.steps().size()));
        return chains;
    }

    /**
     * Builds a derived graph where vertices are type FQNs and edges represent
     * "a method exists that takes this type and returns that type".
     */
    private DirectedMultigraph<String, DerivedEdge> buildDerivedGraph() {
        var derived = new DirectedMultigraph<String, DerivedEdge>(null, null, false);

        // Group CONSUMES and PRODUCES edges by their method signature
        var consumesByMethod = new HashMap<String, List<TypeRelationship>>();
        var producesByMethod = new HashMap<String, List<TypeRelationship>>();

        for (var edge : typeGraph.edgesOf(RelationshipKind.CONSUMES)) {
            if (edge.signature() != null) {
                consumesByMethod.computeIfAbsent(
                        edge.signature().toString(), k -> new ArrayList<>()).add(edge);
            }
        }
        for (var edge : typeGraph.edgesOf(RelationshipKind.PRODUCES)) {
            if (edge.signature() != null) {
                producesByMethod.computeIfAbsent(
                        edge.signature().toString(), k -> new ArrayList<>()).add(edge);
            }
        }

        // For each method that both consumes and produces, create derived edges
        for (var entry : consumesByMethod.entrySet()) {
            var methodKey = entry.getKey();
            var consumes = entry.getValue();
            var produces = producesByMethod.get(methodKey);
            if (produces == null) continue;

            for (var consume : consumes) {
                var inputType = typeGraph.getEdgeSource(consume).fullyQualifiedName();
                for (var produce : produces) {
                    var outputType = typeGraph.getEdgeTarget(produce).fullyQualifiedName();
                    if (inputType.equals(outputType)) continue; // Skip self-loops

                    derived.addVertex(inputType);
                    derived.addVertex(outputType);
                    derived.addEdge(inputType, outputType,
                            new DerivedEdge(consume.signature()));
                }
            }
        }

        // Also add edges for methods with no parameters (only PRODUCES)
        for (var entry : producesByMethod.entrySet()) {
            if (consumesByMethod.containsKey(entry.getKey())) continue;
            for (var produce : entry.getValue()) {
                var declaringType = typeGraph.getEdgeSource(produce).fullyQualifiedName();
                var outputType = typeGraph.getEdgeTarget(produce).fullyQualifiedName();
                if (declaringType.equals(outputType)) continue;

                derived.addVertex(declaringType);
                derived.addVertex(outputType);
                derived.addEdge(declaringType, outputType,
                        new DerivedEdge(produce.signature()));
            }
        }

        return derived;
    }
}
