package com.github.sckwoky.typegraph.model;

import java.util.Map;

/**
 * Normalizes type names: primitives → wrappers, strips array brackets, etc.
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
     * Normalizes a type name:
     * <ul>
     *   <li>Primitives are mapped to their wrapper types</li>
     *   <li>Generic type parameters are stripped ({@code List<String>} → {@code java.util.List})</li>
     *   <li>{@code void} returns {@code null} (not a valid vertex)</li>
     * </ul>
     *
     * @return normalized FQN, or null if the type should not be a vertex (e.g. void)
     */
    public static String normalize(String typeName) {
        if (typeName == null || typeName.isBlank() || "void".equals(typeName)) {
            return null;
        }

        String name = typeName.strip();

        // Strip generic parameters: "java.util.List<java.lang.String>" → "java.util.List"
        int angleBracket = name.indexOf('<');
        if (angleBracket > 0) {
            name = name.substring(0, angleBracket);
        }

        // Strip array dimensions: "int[]" → "int", "String[][]" → "String"
        int bracket = name.indexOf('[');
        if (bracket > 0) {
            name = name.substring(0, bracket);
        }

        // Primitive → wrapper
        String wrapper = PRIMITIVES_TO_WRAPPERS.get(name);
        if (wrapper != null) {
            return wrapper;
        }

        return name;
    }
}
