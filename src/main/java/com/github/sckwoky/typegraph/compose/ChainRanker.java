package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.*;

/**
 * Computes {@link ChainScore} for a candidate chain using structural, reliability,
 * and locality components. Evidence and role-prior scoring is plumbed in but yields
 * 0 contributions until Stage 3 supplies an evidence index.
 */
public class ChainRanker {

    public record Weights(double structural, double reliability, double locality, double evidence, double rolePriors) {
        public static Weights defaults() {
            return new Weights(0.45, 0.25, 0.20, 0.05, 0.05);
        }
    }

    private final Weights weights;
    private final ClassRoleClassifier roleClassifier;

    public ChainRanker(Weights weights, ClassRoleClassifier roleClassifier) {
        this.weights = weights;
        this.roleClassifier = roleClassifier;
    }

    public ChainRanker() {
        this(Weights.defaults(), new ClassRoleClassifier());
    }

    public CandidateChainInternal rank(CandidateChainInternal chain) {
        double structural = scoreStructural(chain);
        double reliability = scoreReliability(chain);
        double locality = scoreLocality(chain);
        double evidence = 0.0;     // populated in Stage 3
        double rolePriors = 0.0;   // populated in Stage 3

        double total =
                weights.structural() * structural
                        + weights.reliability() * reliability
                        + weights.locality() * locality
                        + weights.evidence() * evidence
                        + weights.rolePriors() * rolePriors;

        var score = new ChainScore(total, structural, reliability, locality, evidence, rolePriors);
        var confidence = bucket(total);
        return chain.withScoreAndConfidence(score, confidence);
    }

    // ─── Structural ─────────────────────────────────────────────────

    private double scoreStructural(CandidateChainInternal chain) {
        if (chain.steps().isEmpty()) return 0.0;

        // Resource coverage: fraction of slots bound to non-produced (i.e. real) resources
        int totalSlots = 0;
        int directlyCoveredSlots = 0;
        for (var step : chain.steps()) {
            for (var entry : step.binding().entrySet()) {
                totalSlots++;
                if (!(entry.getValue() instanceof ProducedValue)) directlyCoveredSlots++;
            }
        }
        double resourceCoverage = totalSlots == 0 ? 0.0 : (double) directlyCoveredSlots / totalSlots;

        // Length penalty: shorter is better, with saturation
        double lengthPenalty = 1.0 / (1.0 + 0.3 * (chain.steps().size() - 1));

        // Slot match quality: average penalty for non-EXACT bindings
        double matchQuality = 1.0;
        for (var step : chain.steps()) {
            switch (step.matchKind()) {
                case EXACT -> {}
                case SUBTYPE_COMPATIBLE -> matchQuality -= 0.05;
                case ERASURE_FALLBACK -> matchQuality -= 0.10;
                case UNRESOLVED -> matchQuality -= 0.20;
            }
        }
        matchQuality = Math.max(0.0, matchQuality);

        return 0.5 * resourceCoverage + 0.3 * lengthPenalty + 0.2 * matchQuality;
    }

    // ─── Reliability ────────────────────────────────────────────────

    private double scoreReliability(CandidateChainInternal chain) {
        if (chain.steps().isEmpty()) return 0.0;
        long resolvedCount = chain.steps().stream()
                .filter(s -> s.matchKind() == SignatureMatchKind.EXACT
                        || s.matchKind() == SignatureMatchKind.SUBTYPE_COMPATIBLE)
                .count();
        return (double) resolvedCount / chain.steps().size();
    }

    // ─── Locality ───────────────────────────────────────────────────

    private double scoreLocality(CandidateChainInternal chain) {
        if (chain.steps().isEmpty()) return 0.0;
        String targetType = chain.target().declaringType();
        String targetPackage = packageOf(targetType);

        int sameClassCount = 0;
        int samePackageCount = 0;
        for (var step : chain.steps()) {
            String declaring = step.operator().declaringType();
            if (declaring.equals(targetType)) {
                sameClassCount++;
            } else if (packageOf(declaring).equals(targetPackage)) {
                samePackageCount++;
            }
        }
        int n = chain.steps().size();
        return (1.0 * sameClassCount + 0.6 * samePackageCount) / n;
    }

    private static String packageOf(String fqn) {
        int angle = fqn.indexOf('<');
        String base = angle < 0 ? fqn : fqn.substring(0, angle);
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(0, dot);
    }

    private static ChainConfidence bucket(double total) {
        if (total >= 0.70) return ChainConfidence.HIGH;
        if (total >= 0.40) return ChainConfidence.MEDIUM;
        return ChainConfidence.LOW;
    }
}
