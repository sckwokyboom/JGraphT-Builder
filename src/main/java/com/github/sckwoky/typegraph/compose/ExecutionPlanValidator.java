package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.*;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Validates that a candidate chain is operationally executable.
 * Drops chains that look type-compatible but cannot actually run as a method body.
 */
public class ExecutionPlanValidator {

    private final SignatureMatcher matcher;
    private final SubtypingPolicy policy;

    public ExecutionPlanValidator(SignatureMatcher matcher, SubtypingPolicy policy) {
        this.matcher = matcher;
        this.policy = policy;
    }

    public ValidationResult validate(CandidateChainInternal chain) {
        var reasons = new ArrayList<String>();
        var producedSoFar = new HashSet<String>();

        for (int i = 0; i < chain.steps().size(); i++) {
            var step = chain.steps().get(i);
            var op = step.operator();

            // 1. Every slot is bound
            for (var slot : op.allSlots()) {
                if (!step.binding().containsKey(slot)) {
                    reasons.add("step " + i + ": slot " + slot + " not bound");
                }
            }

            // 2. Topological order: produced values referenced must come from earlier steps
            for (var entry : step.binding().entrySet()) {
                var res = entry.getValue();
                if (res instanceof ProducedValue pv) {
                    if (!producedSoFar.contains(pv.valueId())) {
                        reasons.add("step " + i + ": references produced value " + pv.valueId() +
                                " before its producer");
                    }
                }
            }

            // 3. Receiver compatibility: instance method has a binding for receiver slot
            if (op.hasReceiver() && op.receiverSlot() != null) {
                if (!step.binding().containsKey(op.receiverSlot())) {
                    reasons.add("step " + i + ": instance method missing receiver binding");
                }
            }

            // Record this step's produced value for downstream use
            if (step.producedValue() != null) {
                producedSoFar.add(step.producedValue().valueId());
            }
        }

        // 4. Final type matches target.returnType
        if (chain.steps().isEmpty()) {
            reasons.add("chain is empty");
        } else {
            String finalType = chain.finalProducedType();
            String targetType = chain.target().returnType();
            var kind = matcher.match(finalType, targetType, policy);
            if (!matcher.isAcceptable(kind)) {
                reasons.add("final produced type " + finalType + " not compatible with target return " + targetType);
            }
        }

        return reasons.isEmpty() ? new ValidationResult.Valid() : new ValidationResult.Invalid(reasons);
    }
}
