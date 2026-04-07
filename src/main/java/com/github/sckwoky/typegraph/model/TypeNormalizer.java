package com.github.sckwoky.typegraph.model;

import java.util.Map;

/**
 * Normalizes raw (non-generic) type names: primitives → wrappers, void → null.
 * <p>
 * This class handles only simple type name normalization.
 * All generic type information must be extracted from the AST via
 * {@link com.github.javaparser.resolution.types.ResolvedType} API,
 * never from string parsing.
 */
public final class TypeNormalizer {

    private static final Map<String, String> PRIMITIVES_TO_WRAPPERS = Map.of(
            "boolean", "java.lang.Boolean",
            "byte", "java.lang.Byte",
            "char", "java.lang.Character",
            "short", "java.lang.Short",
            "int", "java.lang.Integer",
            "long", "java.lang.Long",
            "float", "java.lang.Float",
            "double", "java.lang.Double"
    );

    private TypeNormalizer() {}

    /**
     * Normalizes a raw type name (without generics):
     * <ul>
     *   <li>Primitives are mapped to their wrapper types</li>
     *   <li>{@code void} returns {@code null} (not a valid vertex)</li>
     *   <li>All other names pass through unchanged</li>
     * </ul>
     * <p>
     * This method must only be called with simple/raw type names.
     * Generic type parameters are handled exclusively via the JavaParser
     * {@code ResolvedType} AST API in {@code TypeRelationshipExtractor}.
     *
     * @return normalized FQN, or null if the type should not be a vertex (e.g. void)
     */
    public static String normalize(String typeName) {
        if (typeName == null || typeName.isBlank() || "void".equals(typeName)) {
            return null;
        }

        String name = typeName.strip();

        String wrapper = PRIMITIVES_TO_WRAPPERS.get(name);
        if (wrapper != null) {
            return wrapper;
        }

        return name;
    }
}
