package com.github.sckwoky.typegraph.flow.model;

public enum FlowEdgeKind {
    DATA_DEP,        // generic data dependency between values
    ARG_PASS,        // value flowing into a CALL as an argument (label = arg index)
    CALL_RESULT_OF,  // CALL → CALL_RESULT structural edge
    RETURN_DEP,      // value flowing into a RETURN
    DEF_USE,         // LOCAL_DEF → LOCAL_USE
    PHI_INPUT,       // version → MERGE_VALUE
    CONTROL_DEP      // BRANCH/LOOP → child node inside its control region
}
