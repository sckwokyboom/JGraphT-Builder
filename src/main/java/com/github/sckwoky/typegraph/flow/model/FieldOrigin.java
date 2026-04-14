package com.github.sckwoky.typegraph.flow.model;

public enum FieldOrigin {
    THIS,     // this.field or unqualified field of the enclosing class
    OTHER,    // someExpr.field on a different object
    STATIC,   // ClassName.staticField
    UNKNOWN
}
