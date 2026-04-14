package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.*;

import java.util.*;

/**
 * Tracks which resources are available for binding to {@link MethodOperator} slots
 * during chain construction. Maintains both the static {@link AvailableResources}
 * (params, fields, this) and dynamically produced values from earlier steps.
 * <p>
 * This class is the core of "resource accounting" — the chain search uses it to
 * verify, on every step, that all of an operator's slots can be bound.
 */
public class ResourceBudget {

    private final AvailableResources statics;
    private final Map<String, List<AvailableResource>> staticByType;
    private final List<ProducedValue> produced = new ArrayList<>();
    private final SignatureMatcher matcher;
    private final SubtypingPolicy policy;

    public ResourceBudget(AvailableResources statics, SignatureMatcher matcher, SubtypingPolicy policy) {
        this.statics = statics;
        this.staticByType = statics.indexByType();
        this.matcher = matcher;
        this.policy = policy;
    }

    /** Snapshot copy. */
    private ResourceBudget(AvailableResources statics,
                           Map<String, List<AvailableResource>> staticByType,
                           List<ProducedValue> produced,
                           SignatureMatcher matcher,
                           SubtypingPolicy policy) {
        this.statics = statics;
        this.staticByType = staticByType;
        this.produced.addAll(produced);
        this.matcher = matcher;
        this.policy = policy;
    }

    public ResourceBudget snapshot() {
        return new ResourceBudget(statics, staticByType, produced, matcher, policy);
    }

    public List<ProducedValue> producedValues() {
        return List.copyOf(produced);
    }

    public AvailableResources statics() {
        return statics;
    }

    /**
     * Returns all candidate resources that can satisfy this slot, including produced values.
     * Result is ordered: produced values first (prefer pipe-style), then params, then fields, then this.
     */
    public List<AvailableResource> candidatesFor(TypedSlot slot) {
        var out = new ArrayList<AvailableResource>();
        // 1. produced values matching this slot's type
        for (var pv : produced) {
            if (matcher.isAcceptable(matcher.match(pv.typeFqn(), slot.typeFqn(), policy))) {
                out.add(pv);
            }
        }
        // 2. static resources (params, fields, this) matching by type
        // First try exact type lookup, then fall back to scanning all
        var sameType = staticByType.get(slot.typeFqn());
        if (sameType != null) {
            for (var r : sameType) {
                // Avoid duplicates (already added produced)
                if (!out.contains(r)) out.add(r);
            }
        }
        if (policy != SubtypingPolicy.STRICT) {
            for (var r : statics.allStatic()) {
                if (out.contains(r)) continue;
                if (r.typeFqn().equals(slot.typeFqn())) continue;
                if (matcher.isAcceptable(matcher.match(r.typeFqn(), slot.typeFqn(), policy))) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    public boolean canCover(TypedSlot slot) {
        return !candidatesFor(slot).isEmpty();
    }

    public Set<TypedSlot> uncovered(MethodOperator op) {
        var u = new LinkedHashSet<TypedSlot>();
        for (var slot : op.allSlots()) {
            if (!canCover(slot)) u.add(slot);
        }
        return u;
    }

    /** Adds a produced value to the budget (returns a new budget; original is unchanged). */
    public ResourceBudget withProduced(ProducedValue pv) {
        var copy = snapshot();
        copy.produced.add(pv);
        return copy;
    }
}
