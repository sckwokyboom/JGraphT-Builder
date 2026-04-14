package com.github.sckwoky.typegraph.compose.model;

import java.util.Map;

/**
 * One step in a candidate chain: a {@link MethodOperator} with all its slots
 * bound to concrete {@link AvailableResource}s.
 */
public record CandidateStep(
        String stepId,
        MethodOperator operator,
        Map<TypedSlot, AvailableResource> binding,
        SignatureMatchKind matchKind,
        ProducedValue producedValue
) {
    public CandidateStep {
        binding = Map.copyOf(binding);
    }

    public String producedType() {
        return operator.returnType();
    }
}
