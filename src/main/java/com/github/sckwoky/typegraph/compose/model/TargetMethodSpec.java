package com.github.sckwoky.typegraph.compose.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Specification of the target method we are searching chains for.
 * The target method has no body — only a signature plus the fields available
 * on the enclosing class.
 */
public record TargetMethodSpec(
        String declaringType,
        String methodName,
        List<String> paramTypes,
        List<String> paramNames,
        List<AvailableField> fields,
        String returnType,
        boolean isStatic
) {
    public TargetMethodSpec {
        paramTypes = List.copyOf(paramTypes);
        paramNames = List.copyOf(paramNames);
        fields = List.copyOf(fields);
    }

    public AvailableResources toAvailableResources() {
        var params = new ArrayList<AvailableParam>(paramTypes.size());
        for (int i = 0; i < paramTypes.size(); i++) {
            String name = i < paramNames.size() ? paramNames.get(i) : "p" + i;
            params.add(new AvailableParam(i, name, paramTypes.get(i)));
        }
        AvailableThis thisRef = isStatic ? null : new AvailableThis(declaringType);
        return new AvailableResources(params, fields, thisRef);
    }
}
