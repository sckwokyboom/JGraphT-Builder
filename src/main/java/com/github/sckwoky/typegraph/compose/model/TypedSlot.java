package com.github.sckwoky.typegraph.compose.model;

/**
 * A typed slot of a {@link MethodOperator}: either the receiver or one of the parameters.
 *
 * @param index        -1 for receiver, 0..N-1 for params
 * @param slotKind     {@link SlotKind#RECEIVER} or {@link SlotKind#PARAM}
 * @param typeFqn      fully qualified name of the required type
 * @param declaredName parameter name from source (or "this" for receiver), may be null
 */
public record TypedSlot(
        int index,
        SlotKind slotKind,
        String typeFqn,
        String declaredName
) {
    public enum SlotKind { RECEIVER, PARAM }

    public boolean isReceiver() {
        return slotKind == SlotKind.RECEIVER;
    }
}
