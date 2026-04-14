package com.github.sckwoky.typegraph.compose.model;

import java.util.List;

/**
 * Compressed, prompt-ready representation of a candidate chain.
 * Does not carry validation, raw scores, or internal step ids.
 */
public record PromptCandidateChain(
        String availableSummary,
        List<PromptStep> steps,
        String confidenceLabel,
        double totalScore,
        String evidenceSummary
) {
    public PromptCandidateChain {
        steps = List.copyOf(steps);
    }
}
