package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.SignatureMatchKind;
import com.github.sckwoky.typegraph.compose.model.SubtypingPolicy;
import com.github.sckwoky.typegraph.graph.TypeGraph;
import com.github.sckwoky.typegraph.model.RelationshipKind;

import java.util.HashSet;
import java.util.Set;

/**
 * Decides whether a {@code provided} type satisfies a {@code required} type
 * under a given {@link SubtypingPolicy}, and returns a {@link SignatureMatchKind}
 * for use in scoring.
 * <p>
 * Subtype relationships are derived from the existing {@link TypeGraph}'s IS edges.
 */
public class SignatureMatcher {

    private final TypeGraph typeGraph;

    public SignatureMatcher(TypeGraph typeGraph) {
        this.typeGraph = typeGraph;
    }

    public SignatureMatchKind match(String providedFqn, String requiredFqn, SubtypingPolicy policy) {
        if (providedFqn == null || requiredFqn == null) return SignatureMatchKind.UNRESOLVED;
        if (providedFqn.equals(requiredFqn)) return SignatureMatchKind.EXACT;

        if (policy == SubtypingPolicy.STRICT) {
            return SignatureMatchKind.UNRESOLVED;
        }

        if (isSubtype(providedFqn, requiredFqn)) {
            return SignatureMatchKind.SUBTYPE_COMPATIBLE;
        }

        if (policy == SubtypingPolicy.ALLOW_ERASURE) {
            String providedRaw = erase(providedFqn);
            String requiredRaw = erase(requiredFqn);
            if (providedRaw.equals(requiredRaw)) return SignatureMatchKind.ERASURE_FALLBACK;
            if (isSubtype(providedRaw, requiredRaw)) return SignatureMatchKind.ERASURE_FALLBACK;
        }

        return SignatureMatchKind.UNRESOLVED;
    }

    public boolean isAcceptable(SignatureMatchKind kind) {
        return kind == SignatureMatchKind.EXACT
                || kind == SignatureMatchKind.SUBTYPE_COMPATIBLE
                || kind == SignatureMatchKind.ERASURE_FALLBACK;
    }

    /** True if {@code subFqn} is the same as or a transitive subtype of {@code superFqn} (per IS edges). */
    private boolean isSubtype(String subFqn, String superFqn) {
        if (subFqn.equals(superFqn)) return true;
        var visited = new HashSet<String>();
        var stack = new java.util.ArrayDeque<String>();
        stack.push(subFqn);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!visited.add(current)) continue;
            var vertex = typeGraph.findVertex(current).orElse(null);
            if (vertex == null) continue;
            for (var edge : typeGraph.jgraphtGraph().outgoingEdgesOf(vertex)) {
                if (edge.kind() != RelationshipKind.IS) continue;
                String parent = typeGraph.getEdgeTarget(edge).fullyQualifiedName();
                if (parent.equals(superFqn)) return true;
                stack.push(parent);
            }
        }
        return false;
    }

    /** Strip generic parameters: {@code List<String>} → {@code List}. */
    private static String erase(String fqn) {
        int angle = fqn.indexOf('<');
        return angle < 0 ? fqn : fqn.substring(0, angle);
    }
}
