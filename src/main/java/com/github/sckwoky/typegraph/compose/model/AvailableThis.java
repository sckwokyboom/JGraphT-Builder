package com.github.sckwoky.typegraph.compose.model;

public record AvailableThis(String typeFqn) implements AvailableResource {
    @Override
    public String displayName() {
        return "this";
    }

    @Override
    public String resourceId() {
        return "this:" + typeFqn;
    }
}
