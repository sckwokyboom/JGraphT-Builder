package com.github.sckwoky.typegraph.compose.model;

public enum CompositionEdgeKind {
    /** TYPE → METHOD: a parameter slot of the method consumes this type. */
    CONSUMES,
    /** TYPE → METHOD: the receiver slot of the method requires this type. */
    RECEIVER,
    /** METHOD → TYPE: the method produces this type as its return value. */
    PRODUCES,
    /** FIELD → METHOD: the method reads this field on the return-producing chain (Stage 3). */
    READS_FIELD,
    /** METHOD_A → METHOD_B: A real method body shows A's result feeding into B (Stage 3). */
    EVIDENCE_CALLS
}
