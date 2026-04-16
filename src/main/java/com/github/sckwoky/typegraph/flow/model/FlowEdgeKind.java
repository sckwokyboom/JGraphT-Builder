package com.github.sckwoky.typegraph.flow.model;

public enum FlowEdgeKind {
    DATA_DEP,           // generic data dependency between values
    ARG_PASS,           // value flowing into a CALL as an argument (label = arg index)
    CALL_RESULT_OF,     // CALL → CALL_RESULT structural edge
    RETURN_DEP,         // value flowing into a RETURN
    DEF_USE,            // LOCAL_DEF → LOCAL_USE
    PHI_INPUT,          // version → MERGE_VALUE
    CONTROL_DEP,        // BRANCH/LOOP → child node inside its control region
    LEFT_OPERAND,       // left operand of a binary operation
    RIGHT_OPERAND,      // right operand of a binary operation
    UNARY_OPERAND,      // operand of a unary operation
    CAST_OPERAND,       // operand being cast
    INSTANCEOF_OPERAND, // operand of instanceof check
    CONDITION,          // condition expression of a branch/ternary/loop
    THEN_BRANCH,        // then-branch of a ternary or if
    ELSE_BRANCH,        // else-branch of a ternary or if
    ARRAY_REF,          // array reference in array access
    ARRAY_INDEX,        // index expression in array access
    ARRAY_DIM,          // dimension size in array creation
    TERNARY_CONDITION,  // condition of ternary expression
    TERNARY_THEN,       // then-value of ternary expression
    TERNARY_ELSE,       // else-value of ternary expression
    LAMBDA_BODY,        // body of a lambda expression
    LOOP_INIT,          // initializer of a for loop
    LOOP_UPDATE,        // update expression of a for loop
    LOOP_ITERABLE,      // iterable expression of a foreach loop
    RECEIVER,           // receiver object of an instance method call
    ASSIGN_TARGET,      // target (lhs) of an assignment
    ASSIGN_VALUE,       // value (rhs) of an assignment
    THROW_VALUE,        // value being thrown
    ASSERT_MESSAGE,     // optional message in an assert statement
    SYNC_LOCK,          // lock expression of a synchronized block
    CATCH_PARAM,        // catch clause parameter
    TRY_RESOURCE,       // resource in a try-with-resources
    FINALLY_BODY,       // finally block connection
    YIELD_VALUE         // value yielded in a switch expression
}
