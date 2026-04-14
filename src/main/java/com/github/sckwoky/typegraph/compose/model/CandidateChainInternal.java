package com.github.sckwoky.typegraph.compose.model;

import java.util.List;

/**
 * Rich internal representation of a candidate chain. Carries everything needed
 * for ranking and validation. Compress to {@link PromptCandidateChain} for prompts.
 */
public record CandidateChainInternal(
        TargetMethodSpec target,
        List<CandidateStep> steps,
        ChainScore score,
        ChainConfidence confidence,
        EvidenceTrace evidence,
        ValidationResult validation
) {
    public CandidateChainInternal {
        steps = List.copyOf(steps);
    }

    public CandidateChainInternal withScoreAndConfidence(ChainScore newScore, ChainConfidence newConfidence) {
        return new CandidateChainInternal(target, steps, newScore, newConfidence, evidence, validation);
    }

    public CandidateChainInternal withValidation(ValidationResult newValidation) {
        return new CandidateChainInternal(target, steps, score, confidence, evidence, newValidation);
    }

    public String finalProducedType() {
        return steps.isEmpty() ? null : steps.get(steps.size() - 1).producedType();
    }
}
