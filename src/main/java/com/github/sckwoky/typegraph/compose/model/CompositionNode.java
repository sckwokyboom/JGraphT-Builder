package com.github.sckwoky.typegraph.compose.model;

import java.util.Objects;

/**
 * A vertex in the {@link com.github.sckwoky.typegraph.compose.GlobalCompositionGraph}.
 * Sealed: TYPE, METHOD, or FIELD. Equality based on {@link #id()}.
 */
public sealed interface CompositionNode {
    String id();
    CompositionNodeKind kind();
    String displayLabel();

    record TypeNode(String fqn) implements CompositionNode {
        @Override public String id() { return "type:" + fqn; }
        @Override public CompositionNodeKind kind() { return CompositionNodeKind.TYPE; }
        @Override public String displayLabel() { return shortName(fqn); }

        private static String shortName(String fqn) {
            int angle = fqn.indexOf('<');
            String base = angle < 0 ? fqn : fqn.substring(0, angle);
            int dot = base.lastIndexOf('.');
            String simpleBase = dot < 0 ? base : base.substring(dot + 1);
            return angle < 0 ? simpleBase : simpleBase + fqn.substring(angle);
        }
    }

    record MethodNode(MethodOperator operator) implements CompositionNode {
        public MethodNode {
            Objects.requireNonNull(operator);
        }
        @Override public String id() { return "method:" + operator.signature(); }
        @Override public CompositionNodeKind kind() { return CompositionNodeKind.METHOD; }
        @Override public String displayLabel() {
            return operator.signature().declaringType() + "#" + operator.signature().methodName();
        }
    }

    record FieldNode(String declaringType, String fieldName, String fieldType) implements CompositionNode {
        @Override public String id() { return "field:" + declaringType + "#" + fieldName; }
        @Override public CompositionNodeKind kind() { return CompositionNodeKind.FIELD; }
        @Override public String displayLabel() { return declaringType + "#" + fieldName; }
    }
}
