package com.github.sckwoky.typegraph.compose.model;

public record AvailableField(String fieldName, String typeFqn, String declaringType) implements AvailableResource {
    @Override
    public String displayName() {
        return "this." + fieldName;
    }

    @Override
    public String resourceId() {
        return "field:" + declaringType + "#" + fieldName;
    }
}
