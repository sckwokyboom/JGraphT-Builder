package com.github.sckwoky.typegraph.flow.model;

import com.github.sckwoky.typegraph.model.MethodSignature;

import java.util.Objects;

/**
 * A vertex in a {@link com.github.sckwoky.typegraph.flow.MethodFlowGraph}.
 * <p>
 * Equality is based solely on {@code id}, which is unique within a single
 * MethodFlowGraph. Most metadata fields are nullable and only meaningful for
 * specific kinds.
 */
public final class FlowNode {

    private final String id;
    private final FlowNodeKind kind;
    private final String label;
    private final int sourceLine;

    // Optional metadata
    private final String typeFqn;
    private final String variableName;
    private final int variableVersion;          // -1 if not applicable
    private final MethodSignature callSignature; // CALL/CALL_RESULT
    private final CallResolution callResolution; // CALL only
    private final FieldOrigin fieldOrigin;        // FIELD_READ/WRITE
    private final ControlSubtype controlSubtype;  // BRANCH/MERGE/LOOP
    private final String enclosingControlId;      // dominating BRANCH/LOOP id (for slicer)

    public FlowNode(String id, FlowNodeKind kind, String label, int sourceLine,
                    String typeFqn, String variableName, int variableVersion,
                    MethodSignature callSignature, CallResolution callResolution,
                    FieldOrigin fieldOrigin, ControlSubtype controlSubtype,
                    String enclosingControlId) {
        this.id = Objects.requireNonNull(id);
        this.kind = Objects.requireNonNull(kind);
        this.label = label == null ? "" : label;
        this.sourceLine = sourceLine;
        this.typeFqn = typeFqn;
        this.variableName = variableName;
        this.variableVersion = variableVersion;
        this.callSignature = callSignature;
        this.callResolution = callResolution;
        this.fieldOrigin = fieldOrigin;
        this.controlSubtype = controlSubtype == null ? ControlSubtype.NONE : controlSubtype;
        this.enclosingControlId = enclosingControlId;
    }

    public String id() { return id; }
    public FlowNodeKind kind() { return kind; }
    public String label() { return label; }
    public int sourceLine() { return sourceLine; }
    public String typeFqn() { return typeFqn; }
    public String variableName() { return variableName; }
    public int variableVersion() { return variableVersion; }
    public MethodSignature callSignature() { return callSignature; }
    public CallResolution callResolution() { return callResolution; }
    public FieldOrigin fieldOrigin() { return fieldOrigin; }
    public ControlSubtype controlSubtype() { return controlSubtype; }
    public String enclosingControlId() { return enclosingControlId; }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof FlowNode that && id.equals(that.id));
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() {
        return id + "[" + kind + (label.isEmpty() ? "" : " " + label) + "]";
    }
}
