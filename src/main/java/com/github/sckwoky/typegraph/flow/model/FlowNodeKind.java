package com.github.sckwoky.typegraph.flow.model;

public enum FlowNodeKind {
    PARAM,         // method parameter (input)
    THIS_REF,      // reference to enclosing instance
    SUPER_REF,     // reference to superclass instance
    FIELD_READ,    // reading a field
    FIELD_WRITE,   // assignment to a field
    LOCAL_DEF,     // versioned definition of a local variable
    LOCAL_USE,     // use of a local variable
    MERGE_VALUE,   // phi-like merge of variable definitions across branches/loops
    CALL,          // method/constructor invocation operation
    CALL_RESULT,   // value produced by a CALL
    RETURN,        // return statement
    BRANCH,        // control split (if/switch/try/ternary) — see controlSubtype
    MERGE,         // control merge after BRANCH
    LOOP,          // loop summary node — see controlSubtype (for/foreach/while/do)
    LITERAL,       // literal/constant value
    // Expression-level nodes (replace TEMP_EXPR)
    BINARY_OP,     // binary operation (a + b, a && b, etc.)
    UNARY_OP,      // unary operation (!a, -a, ++a, etc.)
    CAST,          // type cast expression
    INSTANCEOF,    // instanceof check
    ARRAY_CREATE,  // array creation (new T[n])
    ARRAY_ACCESS,  // array element access (a[i])
    TERNARY,       // ternary conditional expression (c ? t : e)
    OBJECT_CREATE, // object creation (new T(...))
    LAMBDA,        // lambda expression
    METHOD_REF,    // method reference expression
    ASSIGN,        // assignment expression (compound or simple)
    // Statement-level nodes
    THROW,         // throw statement
    BREAK,         // break statement
    CONTINUE,      // continue statement
    ASSERT,        // assert statement
    SYNCHRONIZED,  // synchronized block
    SWITCH_CASE,   // switch case label
    YIELD          // yield statement (switch expression)
}
