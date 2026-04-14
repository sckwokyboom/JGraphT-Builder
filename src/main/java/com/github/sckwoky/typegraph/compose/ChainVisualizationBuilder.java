package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts a list of {@link CandidateChainInternal} into a JSON payload that the
 * interactive HTML composition viewer can use to highlight individual chains.
 * <p>
 * For each chain we compute:
 * <ul>
 *   <li>{@code nodeIds} — the set of {@link CompositionNode#id()} values that the
 *       chain "touches" (METHOD nodes plus the TYPE nodes used as bound resources
 *       and produced values)</li>
 *   <li>{@code edgeKeys} — fingerprints of the form
 *       {@code source|target|kind|slotIndex} that match the edges produced by
 *       {@link com.github.sckwoky.typegraph.export.CompositionJsonExporter}</li>
 * </ul>
 * The fingerprints let the JS viewer highlight exactly the edges that participate
 * in the chain without depending on numeric edge ids.
 */
public class ChainVisualizationBuilder {

    private final PromptOutputBuilder promptBuilder = new PromptOutputBuilder();

    public String buildJson(List<CandidateChainInternal> chains) {
        var sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < chains.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('\n').append("  ").append(renderChain(i, chains.get(i)));
        }
        if (!chains.isEmpty()) sb.append('\n');
        sb.append(']');
        return sb.toString();
    }

    private String renderChain(int index, CandidateChainInternal chain) {
        var prompt = promptBuilder.build(chain);
        var nodeIds = new LinkedHashSet<String>();
        var edgeKeys = new LinkedHashSet<String>();
        collectHighlights(chain, nodeIds, edgeKeys);

        var sb = new StringBuilder();
        sb.append('{');
        sb.append("\"id\": ").append(index).append(", ");
        sb.append("\"label\": ").append(jsonStr("Candidate " + (index + 1))).append(", ");
        sb.append("\"confidence\": ").append(jsonStr(chain.confidence().name())).append(", ");
        sb.append("\"score\": ").append(roundedScore(chain.score().total())).append(", ");
        sb.append("\"evidenceSummary\": ").append(jsonStr(prompt.evidenceSummary())).append(", ");
        sb.append("\"steps\": [");
        for (int i = 0; i < prompt.steps().size(); i++) {
            if (i > 0) sb.append(", ");
            var step = prompt.steps().get(i);
            sb.append('{')
                    .append("\"displayCall\": ").append(jsonStr(step.displayCall())).append(", ")
                    .append("\"producedType\": ").append(jsonStr(step.producedType()))
                    .append('}');
        }
        sb.append("], ");
        sb.append("\"highlight\": {");
        sb.append("\"nodeIds\": ").append(jsonStrArr(nodeIds)).append(", ");
        sb.append("\"edgeKeys\": ").append(jsonStrArr(edgeKeys));
        sb.append('}');
        sb.append('}');
        return sb.toString();
    }

    /**
     * Walks every step of the chain and records all node ids and edge fingerprints
     * that the chain touches. Edge fingerprints follow the same convention as the
     * JSON exporter so the viewer can match them by string equality.
     */
    private void collectHighlights(CandidateChainInternal chain, Set<String> nodeIds, Set<String> edgeKeys) {
        for (var step : chain.steps()) {
            var op = step.operator();
            String methodId = "method:" + op.signature();
            nodeIds.add(methodId);

            // Receiver edge: TYPE -> METHOD via RECEIVER
            if (op.hasReceiver() && op.receiverSlot() != null) {
                String recvTypeId = "type:" + op.receiverSlot().typeFqn();
                nodeIds.add(recvTypeId);
                edgeKeys.add(edgeKey(recvTypeId, methodId, "RECEIVER", -1));

                // Bound receiver resource (if it's a different type, e.g. an AvailableThis
                // whose declared type matches the receiver requirement) — we still highlight
                // its TYPE node so the user sees it light up.
                var bound = step.binding().get(op.receiverSlot());
                if (bound != null) nodeIds.add("type:" + bound.typeFqn());
            }

            // Param slots: each param's source TYPE -> METHOD via CONSUMES with slotIndex
            for (var slot : op.paramSlots()) {
                String paramTypeId = "type:" + slot.typeFqn();
                nodeIds.add(paramTypeId);
                edgeKeys.add(edgeKey(paramTypeId, methodId, "CONSUMES", slot.index()));

                var bound = step.binding().get(slot);
                if (bound != null) nodeIds.add("type:" + bound.typeFqn());
            }

            // Produced edge: METHOD -> returnType via PRODUCES
            String producedTypeId = "type:" + op.returnType();
            nodeIds.add(producedTypeId);
            edgeKeys.add(edgeKey(methodId, producedTypeId, "PRODUCES", -1));
        }
    }

    private static String edgeKey(String src, String tgt, String kind, int slotIndex) {
        return src + "|" + tgt + "|" + kind + "|" + slotIndex;
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String jsonStrArr(Set<String> items) {
        var sb = new StringBuilder("[");
        boolean first = true;
        for (var s : items) {
            if (!first) sb.append(", ");
            sb.append(jsonStr(s));
            first = false;
        }
        return sb.append(']').toString();
    }

    private static String roundedScore(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
