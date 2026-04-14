package com.github.sckwoky.typegraph.compose.model;

import java.util.Set;

/**
 * Evidence statistics attached to a candidate chain.
 * On Stage 1 this is a placeholder with all-zero/empty values; populated by Stage 3.
 */
public record EvidenceTrace(
        int seenInMethodCount,
        int sameDeclaringPackageCount,
        boolean fullyResolved,
        Set<String> evidenceMethodIds
) {
    public EvidenceTrace {
        evidenceMethodIds = Set.copyOf(evidenceMethodIds);
    }

    public static EvidenceTrace empty() {
        return new EvidenceTrace(0, 0, true, Set.of());
    }
}
