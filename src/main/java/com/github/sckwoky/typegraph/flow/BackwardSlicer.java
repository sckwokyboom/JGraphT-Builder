package com.github.sckwoky.typegraph.flow;

import com.github.sckwoky.typegraph.flow.model.*;

import java.util.*;

/**
 * Per-return backward slicer over a {@link MethodFlowGraph}.
 * <p>
 * Each {@code RETURN} node yields its own slice. Starting from the return node,
 * the slicer walks backward along all data-bearing edges
 * ({@code DATA_DEP}, {@code ARG_PASS}, {@code CALL_RESULT_OF}, {@code RETURN_DEP},
 * {@code DEF_USE}, {@code PHI_INPUT}). Control nodes (BRANCH/LOOP/MERGE) are
 * <b>not</b> followed transitively via {@code CONTROL_DEP}; instead, after the
 * data closure is built, every data-relevant node's {@code enclosingControlId}
 * is added directly. This keeps the slice tight: a BRANCH or LOOP appears in
 * the slice only when it actually dominates a node that was already pulled in
 * by data dependencies.
 */
public class BackwardSlicer {

    private static final Set<FlowEdgeKind> DATA_KINDS = EnumSet.of(
            FlowEdgeKind.DATA_DEP,
            FlowEdgeKind.ARG_PASS,
            FlowEdgeKind.CALL_RESULT_OF,
            FlowEdgeKind.RETURN_DEP,
            FlowEdgeKind.DEF_USE,
            FlowEdgeKind.PHI_INPUT
    );

    /**
     * @return one slice per RETURN node, in source order
     */
    public Map<FlowNode, Set<FlowNode>> slicePerReturn(MethodFlowGraph graph) {
        var perReturn = new LinkedHashMap<FlowNode, Set<FlowNode>>();
        var byId = new HashMap<String, FlowNode>();
        for (var n : graph.nodes()) byId.put(n.id(), n);

        for (var ret : graph.returnNodes()) {
            var slice = sliceFrom(graph, ret, byId);
            perReturn.put(ret, slice);
        }
        return perReturn;
    }

    public Set<FlowNode> sliceFrom(MethodFlowGraph graph, FlowNode start, Map<String, FlowNode> byId) {
        var visited = new LinkedHashSet<FlowNode>();
        var queue = new ArrayDeque<FlowNode>();
        queue.add(start);

        while (!queue.isEmpty()) {
            var node = queue.poll();
            if (!visited.add(node)) continue;
            for (var edge : graph.incomingEdgesOf(node)) {
                if (!DATA_KINDS.contains(edge.kind())) continue;
                var src = graph.getEdgeSource(edge);
                if (!visited.contains(src)) queue.add(src);
            }
        }

        // Pull in dominating control nodes (BRANCH/LOOP) by enclosingControlId tag.
        var controlAdditions = new LinkedHashSet<FlowNode>();
        for (var n : visited) {
            var encId = n.enclosingControlId();
            while (encId != null) {
                var control = byId.get(encId);
                if (control == null || !controlAdditions.add(control)) break;
                encId = control.enclosingControlId();
            }
        }
        visited.addAll(controlAdditions);
        return visited;
    }

    /**
     * Optional: union of all per-return slices, useful when a single highlight
     * is wanted regardless of which return is reached.
     */
    public Set<FlowNode> mergeSlices(Map<FlowNode, Set<FlowNode>> perReturn) {
        var merged = new LinkedHashSet<FlowNode>();
        for (var s : perReturn.values()) merged.addAll(s);
        return merged;
    }
}
