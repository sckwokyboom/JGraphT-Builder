package com.github.sckwoky.typegraph.compose.model;

/**
 * A concrete, identified resource available in the body of a (target) method.
 * Resources have <b>identity</b>, not just type — two fields of the same type are
 * different resources, and the formatter shows the LLM exactly which one is used.
 */
public sealed interface AvailableResource
        permits AvailableParam, AvailableField, AvailableThis, ProducedValue {

    String typeFqn();

    /** Short display name suitable for prompts (e.g. "id", "this.repository", "$1"). */
    String displayName();

    /** Stable identifier inside a single chain. */
    String resourceId();
}
