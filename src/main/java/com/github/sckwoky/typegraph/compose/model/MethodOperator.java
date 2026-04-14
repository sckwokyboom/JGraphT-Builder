package com.github.sckwoky.typegraph.compose.model;

import com.github.sckwoky.typegraph.model.MethodSignature;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A method modeled as a multi-input operator (hyperedge).
 * <p>
 * The operator is "applicable" only when ALL its slots are covered by some
 * available resource or earlier produced value (see {@link com.github.sckwoky.typegraph.compose.ResourceBudget}).
 *
 * @param signature     full method signature
 * @param declaringType FQN of the declaring class
 * @param returnType    FQN of the return type ("void" or wrapped void if applicable)
 * @param paramSlots    ordered parameter slots
 * @param hasReceiver   true for instance methods (then a {@link TypedSlot.SlotKind#RECEIVER} slot exists)
 * @param isConstructor true if methodName == "&lt;init&gt;"
 */
public record MethodOperator(
        MethodSignature signature,
        String declaringType,
        String returnType,
        List<TypedSlot> paramSlots,
        TypedSlot receiverSlot,
        boolean hasReceiver,
        boolean isConstructor
) {
    public MethodOperator {
        Objects.requireNonNull(signature);
        Objects.requireNonNull(declaringType);
        Objects.requireNonNull(returnType);
        paramSlots = List.copyOf(paramSlots);
    }

    /**
     * Returns receiver slot followed by all parameter slots, in order.
     */
    public List<TypedSlot> allSlots() {
        var all = new ArrayList<TypedSlot>(paramSlots.size() + (hasReceiver ? 1 : 0));
        if (hasReceiver && receiverSlot != null) {
            all.add(receiverSlot);
        }
        all.addAll(paramSlots);
        return all;
    }

    /**
     * Builds a MethodOperator from a MethodSignature using the heuristic:
     * methodName "&lt;init&gt;" → constructor (no receiver, returns declaring type);
     * otherwise instance method (receiver = declaring type).
     * <p>
     * <b>Known limitation</b>: cannot detect static methods without explicit isStatic info.
     * Static methods are misclassified as instance methods.
     */
    public static MethodOperator fromSignature(MethodSignature sig) {
        boolean isCtor = "<init>".equals(sig.methodName());
        var paramSlots = new ArrayList<TypedSlot>(sig.parameterTypes().size());
        for (int i = 0; i < sig.parameterTypes().size(); i++) {
            paramSlots.add(new TypedSlot(i, TypedSlot.SlotKind.PARAM, sig.parameterTypes().get(i), "p" + i));
        }
        TypedSlot receiver = null;
        boolean hasReceiver = false;
        if (!isCtor) {
            receiver = new TypedSlot(-1, TypedSlot.SlotKind.RECEIVER, sig.declaringType(), "this");
            hasReceiver = true;
        }
        return new MethodOperator(sig, sig.declaringType(), sig.returnType(), paramSlots, receiver, hasReceiver, isCtor);
    }
}
