package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.*;
import com.github.sckwoky.typegraph.graph.TypeGraph;
import com.github.sckwoky.typegraph.model.MethodSignature;
import com.github.sckwoky.typegraph.model.RelationshipKind;
import com.github.sckwoky.typegraph.model.TypeRelationship;
import com.github.sckwoky.typegraph.model.TypeVertex;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link GlobalCompositionGraph} from an existing {@link TypeGraph}.
 * <p>
 * Maps:
 * <ul>
 *   <li>PRODUCES edges → MethodOperator + METHOD node + PRODUCES edge to return TYPE</li>
 *   <li>For each method's CONSUMES edges → CONSUMES edges from param TYPE to METHOD (with slot index)</li>
 *   <li>For instance methods (heuristic: methodName != "&lt;init&gt;") → RECEIVER edge from declaring TYPE to METHOD</li>
 *   <li>HAS edges → FIELD nodes</li>
 * </ul>
 * <p>
 * Subtype relationships from IS edges are <b>not</b> exposed as composition edges; they
 * are consulted indirectly via {@link SignatureMatcher} when subtyping policies allow.
 */
public class CompositionGraphBuilder {

    public GlobalCompositionGraph build(TypeGraph typeGraph) {
        var graph = new GlobalCompositionGraph();

        // Index PRODUCES edges by signature → return-edge (so we can map every method)
        Map<String, TypeRelationship> producesBySig = new HashMap<>();
        for (var edge : typeGraph.edgesOf(RelationshipKind.PRODUCES)) {
            if (edge.signature() == null) continue;
            producesBySig.put(edge.signature().toString(), edge);
        }

        // Index CONSUMES edges by signature
        Map<String, Set<TypeRelationship>> consumesBySig = new HashMap<>();
        for (var edge : typeGraph.edgesOf(RelationshipKind.CONSUMES)) {
            if (edge.signature() == null) continue;
            consumesBySig.computeIfAbsent(edge.signature().toString(), k -> new HashSet<>()).add(edge);
        }

        // Build METHOD nodes from PRODUCES (each method appears once in PRODUCES)
        for (var entry : producesBySig.entrySet()) {
            MethodSignature sig = entry.getValue().signature();
            MethodOperator op = MethodOperator.fromSignature(sig);
            var methodNode = graph.addMethod(op);

            // PRODUCES edge: METHOD → return TYPE
            var returnTypeNode = graph.getOrCreateType(sig.returnType());
            graph.addEdge(methodNode, returnTypeNode, new CompositionEdge(CompositionEdgeKind.PRODUCES));

            // RECEIVER edge: declaring TYPE → METHOD (only if instance method)
            if (op.hasReceiver()) {
                var declaringTypeNode = graph.getOrCreateType(op.declaringType());
                graph.addEdge(declaringTypeNode, methodNode, new CompositionEdge(CompositionEdgeKind.RECEIVER));
            }

            // CONSUMES edges: param TYPE → METHOD, one per slot (preserve slot index)
            for (var slot : op.paramSlots()) {
                var paramTypeNode = graph.getOrCreateType(slot.typeFqn());
                graph.addEdge(paramTypeNode, methodNode,
                        new CompositionEdge(CompositionEdgeKind.CONSUMES, slot.index(), 1));
            }
        }

        // FIELD nodes from HAS edges
        for (var edge : typeGraph.edgesOf(RelationshipKind.HAS)) {
            TypeVertex declaring = typeGraph.getEdgeSource(edge);
            TypeVertex fieldType = typeGraph.getEdgeTarget(edge);
            // The HAS edge does not carry the field name in this codebase; synthesize one.
            // Use the field type's short name as a placeholder. Real field names come from
            // TargetMethodSpec when the user invokes --find-chains.
            String synthName = fieldType.fullyQualifiedName();
            graph.addField(declaring.fullyQualifiedName(), synthName, fieldType.fullyQualifiedName());
        }

        return graph;
    }
}
