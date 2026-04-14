package com.github.sckwoky.typegraph.compose.model;

public enum SignatureMatchKind {
    /** Exact FQN equality. */
    EXACT,
    /** Provided type is a subtype of the required type (according to subtyping policy). */
    SUBTYPE_COMPATIBLE,
    /** Match holds only after generic erasure (raw type equality). */
    ERASURE_FALLBACK,
    /** Type was not resolved by the symbol solver. */
    UNRESOLVED
}
