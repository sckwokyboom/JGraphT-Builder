package com.github.sckwoky.typegraph.query;

import com.github.sckwoky.typegraph.model.MethodSignature;

import java.util.List;

/**
 * An ordered chain of method calls connecting input types to an output type.
 *
 * @param steps    the method signatures in call order
 * @param inputTypes  the starting types (method parameter types of the target method)
 * @param outputType  the ending type (return type of the target method)
 */
public record MethodChain(
        List<MethodSignature> steps,
        List<String> inputTypes,
        String outputType
) {
    public MethodChain {
        steps = List.copyOf(steps);
        inputTypes = List.copyOf(inputTypes);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("Chain: ");
        sb.append(String.join(", ", inputTypes));
        sb.append(" → ");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(" → ");
            var s = steps.get(i);
            sb.append(s.declaringType()).append(".").append(s.methodName()).append("()");
        }
        sb.append(" → ").append(outputType);
        return sb.toString();
    }
}
