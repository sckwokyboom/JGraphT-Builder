package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.*;

import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Compresses {@link CandidateChainInternal} into {@link PromptCandidateChain},
 * dropping internal step ids, validation details, and raw scores.
 */
public class PromptOutputBuilder {

    public PromptCandidateChain build(CandidateChainInternal chain) {
        var steps = new ArrayList<PromptStep>();
        for (var step : chain.steps()) {
            steps.add(new PromptStep(renderCall(step), shortName(step.producedType())));
        }
        String availableSummary = renderAvailable(chain.target().toAvailableResources());
        String evidenceSummary = renderEvidence(chain.evidence());
        return new PromptCandidateChain(
                availableSummary,
                steps,
                chain.confidence().name(),
                chain.score().total(),
                evidenceSummary
        );
    }

    private String renderCall(CandidateStep step) {
        var op = step.operator();
        var sb = new StringBuilder();

        // Receiver / static / constructor
        AvailableResource receiver = op.hasReceiver() ? step.binding().get(op.receiverSlot()) : null;
        if (op.isConstructor()) {
            sb.append("new ").append(shortName(op.declaringType()));
        } else if (receiver != null) {
            sb.append(receiver.displayName()).append('.').append(op.signature().methodName());
        } else {
            // No receiver and not a constructor → assume static call
            sb.append(shortName(op.declaringType())).append('.').append(op.signature().methodName());
        }

        sb.append('(');
        var args = op.paramSlots().stream()
                .map(slot -> {
                    var bound = step.binding().get(slot);
                    return bound != null ? bound.displayName() : "?";
                })
                .collect(Collectors.joining(", "));
        sb.append(args).append(')');
        return sb.toString();
    }

    private String renderAvailable(AvailableResources avail) {
        var sb = new StringBuilder();
        for (var p : avail.params()) {
            sb.append("  - param: ").append(p.name()).append(" (").append(shortName(p.typeFqn())).append(")\n");
        }
        for (var f : avail.fields()) {
            sb.append("  - field: this.").append(f.fieldName()).append(" (").append(shortName(f.typeFqn())).append(")\n");
        }
        if (avail.thisRef() != null) {
            sb.append("  - this:  ").append(shortName(avail.thisRef().typeFqn())).append('\n');
        }
        return sb.toString();
    }

    private String renderEvidence(EvidenceTrace trace) {
        if (trace == null) return "";
        if (trace.seenInMethodCount() == 0 && trace.evidenceMethodIds().isEmpty()) {
            return "no evidence (Stage 1: structural-only ranking)";
        }
        return "seen in " + trace.seenInMethodCount() + " methods, "
                + (trace.fullyResolved() ? "fully resolved" : "partially resolved");
    }

    private static String shortName(String fqn) {
        if (fqn == null) return "?";
        int angle = fqn.indexOf('<');
        String base = angle < 0 ? fqn : fqn.substring(0, angle);
        int dot = base.lastIndexOf('.');
        String simple = dot < 0 ? base : base.substring(dot + 1);
        return angle < 0 ? simple : simple + fqn.substring(angle);
    }
}
