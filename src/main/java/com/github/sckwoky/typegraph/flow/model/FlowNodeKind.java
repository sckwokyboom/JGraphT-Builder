package com.github.sckwoky.typegraph.flow.model;

public enum FlowNodeKind {
    PARAM,         // method parameter (input)
    THIS_REF,      // reference to enclosing instance
    FIELD_READ,    // reading a field
    FIELD_WRITE,   // assignment to a field
    LOCAL_DEF,     // versioned definition of a local variable
    LOCAL_USE,     // use of a local variable
    TEMP_EXPR,     // intermediate expression result (binary, cast, etc.)
    MERGE_VALUE,   // phi-like merge of variable definitions across branches/loops
    CALL,          // method/constructor invocation operation
    CALL_RESULT,   // value produced by a CALL
    RETURN,        // return statement
    BRANCH,        // control split (if/switch/try/ternary) — see controlSubtype
    MERGE,         // control merge after BRANCH
    LOOP,          // loop summary node — see controlSubtype (for/foreach/while/do)
    LITERAL        // literal/constant value
}
