package com.github.sckwoky.typegraph.compose.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static (non-produced) resources available to the target method body.
 * Produced values are tracked separately by {@link com.github.sckwoky.typegraph.compose.ResourceBudget}.
 */
public record AvailableResources(
        List<AvailableParam> params,
        List<AvailableField> fields,
        AvailableThis thisRef
) {
    public AvailableResources {
        params = List.copyOf(params);
        fields = List.copyOf(fields);
    }

    /**
     * Returns all static resources flattened (params + fields + this if present).
     */
    public List<AvailableResource> allStatic() {
        var all = new ArrayList<AvailableResource>();
        all.addAll(params);
        all.addAll(fields);
        if (thisRef != null) all.add(thisRef);
        return all;
    }

    /**
     * Builds an index from FQN → list of static resources of that type, for fast lookup.
     */
    public Map<String, List<AvailableResource>> indexByType() {
        var index = new HashMap<String, List<AvailableResource>>();
        for (var r : allStatic()) {
            index.computeIfAbsent(r.typeFqn(), k -> new ArrayList<>()).add(r);
        }
        return index;
    }
}
