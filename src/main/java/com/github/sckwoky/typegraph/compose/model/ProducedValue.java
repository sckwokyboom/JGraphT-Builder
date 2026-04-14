package com.github.sckwoky.typegraph.compose.model;

/**
 * A value produced by an earlier step in a candidate chain.
 *
 * @param producerStepId id of the {@link CandidateStep} that produced this value
 * @param valueId        unique id within the chain ("$1", "$2", ...)
 * @param typeFqn        FQN of the produced type
 */
public record ProducedValue(String producerStepId, String valueId, String typeFqn) implements AvailableResource {
    @Override
    public String displayName() {
        return valueId;
    }

    @Override
    public String resourceId() {
        return valueId;
    }
}
