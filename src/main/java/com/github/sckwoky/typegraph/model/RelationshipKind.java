package com.github.sckwoky.typegraph.model;

public enum RelationshipKind {
    /** Parameter type → declaring type: a method of the declaring type consumes this type. */
    CONSUMES,
    /** Declaring type → return type: a method of the declaring type produces this type. */
    PRODUCES,
    /** Subtype → supertype: extends or implements relationship. */
    IS,
    /** Declaring type → field type: the declaring type has a field of this type. */
    HAS
}
