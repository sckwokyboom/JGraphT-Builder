package com.github.sckwoky.typegraph.compose.model;

/**
 * Decomposed scoring of a candidate chain.
 * Subscores are not normalized to a fixed range; only their weighted sum
 * (in {@code total}) is comparable across chains for the same target.
 */
public record ChainScore(
        double total,
        double structural,
        double reliability,
        double locality,
        double evidence,
        double rolePriors
) {
    public static ChainScore zero() {
        return new ChainScore(0, 0, 0, 0, 0, 0);
    }
}
