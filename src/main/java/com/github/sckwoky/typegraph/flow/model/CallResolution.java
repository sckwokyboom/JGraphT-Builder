package com.github.sckwoky.typegraph.flow.model;

public enum CallResolution {
    /** Symbol solver returned a full method signature. */
    RESOLVED,
    /** Method name and arity known, but receiver/param types not fully resolved. */
    PARTIALLY_RESOLVED,
    /** Only the textual call expression is available. */
    UNRESOLVED
}
