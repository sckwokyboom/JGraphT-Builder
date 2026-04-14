package com.github.sckwoky.typegraph.flow.model;

/**
 * Subdivision used by BRANCH / MERGE / LOOP nodes so the viewer can color and
 * label them differently while keeping {@link FlowNodeKind} small.
 */
public enum ControlSubtype {
    IF,
    SWITCH,
    TRY,
    CATCH,
    FINALLY,
    TERNARY,
    FOR,
    FOREACH,
    WHILE,
    DO,
    NONE
}
