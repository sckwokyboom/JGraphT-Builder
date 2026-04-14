package com.github.sckwoky.typegraph.compose.model;

public record AvailableParam(int index, String name, String typeFqn) implements AvailableResource {
    @Override
    public String displayName() {
        return name;
    }

    @Override
    public String resourceId() {
        return "param:" + index;
    }
}
