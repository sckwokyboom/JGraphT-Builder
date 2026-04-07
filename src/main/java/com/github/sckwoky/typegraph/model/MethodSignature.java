package com.github.sckwoky.typegraph.model;

import java.util.List;

/**
 * Captures the signature of a method for provenance tracking on CONSUMES/PRODUCES edges.
 *
 * @param declaringType fully qualified name of the declaring type
 * @param methodName    simple method name (or {@code "<init>"} for constructors)
 * @param parameterTypes fully qualified names of parameter types
 * @param returnType    fully qualified name of the return type (or {@code "void"})
 */
public record MethodSignature(
        String declaringType,
        String methodName,
        List<String> parameterTypes,
        String returnType
) {
    public MethodSignature {
        parameterTypes = List.copyOf(parameterTypes);
    }

    @Override
    public String toString() {
        return declaringType + "#" + methodName + "(" + String.join(", ", parameterTypes) + "): " + returnType;
    }
}
