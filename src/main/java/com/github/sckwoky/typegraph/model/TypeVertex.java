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

    /**
     * Returns a short display name for this type.
     * For {@code java.util.List<java.lang.String>} returns {@code List<String>}.
     */
    public String shortName() {
        return shortName(fullyQualifiedName);
    }

    /**
     * Extracts a short display name from an FQN, handling generics correctly.
     * Strips package prefixes from both the base type and all type arguments.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code java.util.List<java.lang.String>} → {@code List<String>}</li>
     *   <li>{@code java.util.Map<java.lang.String, java.lang.Integer>} → {@code Map<String, Integer>}</li>
     *   <li>{@code com.example.Foo} → {@code Foo}</li>
     * </ul>
     */
    static String shortName(String fqn) {
        int angleIndex = fqn.indexOf('<');
        if (angleIndex < 0) {
            // No generics: simple lastIndexOf('.')
            int dot = fqn.lastIndexOf('.');
            return dot >= 0 ? fqn.substring(dot + 1) : fqn;
        }

        // Has generics: shorten the base type and each type argument separately
        String baseFqn = fqn.substring(0, angleIndex);
        int baseDot = baseFqn.lastIndexOf('.');
        String shortBase = baseDot >= 0 ? baseFqn.substring(baseDot + 1) : baseFqn;

        // Extract the content between < and the final >
        // Use depth tracking to find matching > for the outermost <
        String argsContent = fqn.substring(angleIndex + 1, fqn.length() - 1);

        // Split top-level type arguments respecting nested < >
        var shortArgs = new java.util.ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < argsContent.length(); i++) {
            char c = argsContent.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                shortArgs.add(shortName(argsContent.substring(start, i).strip()));
                start = i + 1;
            }
        }
        shortArgs.add(shortName(argsContent.substring(start).strip()));

        return shortBase + "<" + String.join(", ", shortArgs) + ">";
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
