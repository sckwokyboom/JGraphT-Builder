package com.github.sckwoky.typegraph.flow;

import com.github.sckwoky.typegraph.flow.model.*;
import com.github.sckwoky.typegraph.model.MethodSignature;

import java.util.*;

/**
 * Extracts call-centric compressed chains from a sliced {@link MethodFlowGraph}.
 * Technical nodes (LOCAL_DEF/USE, TEMP_EXPR, MERGE_VALUE, MERGE, BRANCH, LOOP)
 * are skipped; only PARAM, FIELD_READ, CALL, CALL_RESULT and RETURN are kept.
 * <p>
 * Bounded enumeration: simple paths only, max depth and a per-(source, return)
 * cap to keep the result tractable on branching methods.
 */
public class FlowChainExtractor {

    public record EvidenceChainSource(FlowNodeKind kind, String name, String typeFqn, FieldOrigin origin) {}

    public record EvidenceChain(
            MethodSignature enclosingMethod,
            EvidenceChainSource source,
            FlowNode returnNode,
            List<MethodSignature> orderedCalls,
            List<String> intermediateProducedTypes,
            EvidenceContextKind contextKind
    ) {
        public EvidenceChain {
            orderedCalls = List.copyOf(orderedCalls);
            intermediateProducedTypes = List.copyOf(intermediateProducedTypes);
        }
    }

    public record Options(int maxDepth, int topKPerPair) {
        public static Options defaults() { return new Options(20, 25); }
    }

    public List<EvidenceChain> extract(MethodFlowGraph graph,
                                       Map<FlowNode, Set<FlowNode>> perReturnSlices,
                                       Options options) {
        var out = new ArrayList<EvidenceChain>();
        for (var entry : perReturnSlices.entrySet()) {
            var ret = entry.getKey();
            var slice = entry.getValue();
            var sources = new ArrayList<FlowNode>();
            for (var n : slice) {
                if (n.kind() == FlowNodeKind.PARAM || n.kind() == FlowNodeKind.FIELD_READ) {
                    sources.add(n);
                }
            }
            for (var src : sources) {
                int kept = 0;
                var paths = enumeratePaths(graph, src, ret, slice, options.maxDepth());
                for (var path : paths) {
                    if (kept >= options.topKPerPair()) break;
                    var compressed = compress(path);
                    if (compressed.calls().isEmpty()) continue;
                    out.add(new EvidenceChain(
                            graph.methodSignature(),
                            describeSource(src),
                            ret,
                            compressed.calls(),
                            compressed.producedTypes(),
                            classifyContext(path)
                    ));
                    kept++;
                }
            }
        }
        return out;
    }

    private List<List<FlowNode>> enumeratePaths(MethodFlowGraph graph, FlowNode src, FlowNode tgt,
                                                Set<FlowNode> slice, int maxDepth) {
        var results = new ArrayList<List<FlowNode>>();
        dfs(graph, src, tgt, slice, new ArrayDeque<>(), new HashSet<>(), maxDepth, results);
        return results;
    }

    private void dfs(MethodFlowGraph graph, FlowNode current, FlowNode tgt, Set<FlowNode> slice,
                     Deque<FlowNode> stack, Set<FlowNode> visited, int remaining,
                     List<List<FlowNode>> results) {
        if (results.size() >= 200) return;
        stack.push(current);
        visited.add(current);
        try {
            if (current.equals(tgt)) {
                var path = new ArrayList<>(stack);
                Collections.reverse(path);
                results.add(path);
                return;
            }
            if (remaining <= 0) return;
            for (var edge : graph.outgoingEdgesOf(current)) {
                if (edge.kind() == FlowEdgeKind.CONTROL_DEP) continue;
                var next = graph.getEdgeTarget(edge);
                if (visited.contains(next)) continue;
                if (!slice.contains(next)) continue;
                dfs(graph, next, tgt, slice, stack, visited, remaining - 1, results);
            }
        } finally {
            stack.pop();
            visited.remove(current);
        }
    }

    private record Compressed(List<MethodSignature> calls, List<String> producedTypes) {}

    private Compressed compress(List<FlowNode> path) {
        var calls = new ArrayList<MethodSignature>();
        var produced = new ArrayList<String>();
        for (var n : path) {
            if (n.kind() == FlowNodeKind.CALL && n.callSignature() != null) {
                calls.add(n.callSignature());
                if (n.typeFqn() != null) produced.add(n.typeFqn());
            }
        }
        return new Compressed(calls, produced);
    }

    private EvidenceChainSource describeSource(FlowNode n) {
        return new EvidenceChainSource(n.kind(), n.variableName(), n.typeFqn(), n.fieldOrigin());
    }

    private EvidenceContextKind classifyContext(List<FlowNode> path) {
        boolean inLoop = false, inBranch = false, inException = false, hasUnresolved = false;
        for (var n : path) {
            if (n.callResolution() == CallResolution.UNRESOLVED && n.kind() == FlowNodeKind.CALL) {
                hasUnresolved = true;
            }
            if (n.enclosingControlId() != null) {
                // We don't track per-id subtype here; default to BRANCH_DERIVED
                inBranch = true;
            }
        }
        if (hasUnresolved) return EvidenceContextKind.UNRESOLVED_CALL_PRESENT;
        if (inLoop) return EvidenceContextKind.LOOP_SUMMARY_DERIVED;
        if (inException) return EvidenceContextKind.EXCEPTION_DERIVED;
        if (inBranch) return EvidenceContextKind.BRANCH_DERIVED;
        return EvidenceContextKind.STRAIGHT_LINE;
    }
}
