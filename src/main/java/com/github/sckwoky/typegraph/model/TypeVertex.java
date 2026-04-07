package com.github.sckwoky.typegraph.model;

import java.util.Objects;

/**
 * A vertex in the type graph representing a Java type.
 * Equality is based solely on {@code fullyQualifiedName} so that
 * JGraphT can look up vertices by type name.
 */
public final class TypeVertex {

    private final String fullyQualifiedName;
    private TypeKind kind;

    public TypeVertex(String fullyQualifiedName, TypeKind kind) {
        this.fullyQualifiedName = Objects.requireNonNull(fullyQualifiedName);
        this.kind = Objects.requireNonNull(kind);
    }

    public String fullyQualifiedName() {
        return fullyQualifiedName;
    }

    public TypeKind kind() {
        return kind;
    }

    public void setKind(TypeKind kind) {
        this.kind = Objects.requireNonNull(kind);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof TypeVertex that
                && fullyQualifiedName.equals(that.fullyQualifiedName));
    }

    @Override
    public int hashCode() {
        return fullyQualifiedName.hashCode();
    }

    @Override
    public String toString() {
        return fullyQualifiedName + " [" + kind + "]";
    }
}
