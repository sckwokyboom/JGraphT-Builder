package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.*;
import com.github.sckwoky.typegraph.graph.TypeGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceBudgetTest {

    @Test
    void coversAvailableParamByExactType() {
        var resources = new AvailableResources(
                List.of(new AvailableParam(0, "name", "java.lang.String")),
                List.of(),
                null
        );
        var matcher = new SignatureMatcher(new TypeGraph());
        var budget = new ResourceBudget(resources, matcher, SubtypingPolicy.STRICT);

        var slot = new TypedSlot(0, TypedSlot.SlotKind.PARAM, "java.lang.String", "p0");
        assertThat(budget.canCover(slot)).isTrue();
        assertThat(budget.candidatesFor(slot)).hasSize(1);
    }

    @Test
    void cannotCoverWhenTypeMissingAndStrict() {
        var resources = new AvailableResources(
                List.of(new AvailableParam(0, "name", "java.lang.String")),
                List.of(),
                null
        );
        var matcher = new SignatureMatcher(new TypeGraph());
        var budget = new ResourceBudget(resources, matcher, SubtypingPolicy.STRICT);

        var slot = new TypedSlot(0, TypedSlot.SlotKind.PARAM, "com.example.Dog", "dog");
        assertThat(budget.canCover(slot)).isFalse();
    }

    @Test
    void twoFieldsOfSameTypeProduceTwoCandidates() {
        var resources = new AvailableResources(
                List.of(),
                List.of(
                        new AvailableField("primary", "com.example.Mapper", "com.example.Service"),
                        new AvailableField("secondary", "com.example.Mapper", "com.example.Service")
                ),
                new AvailableThis("com.example.Service")
        );
        var matcher = new SignatureMatcher(new TypeGraph());
        var budget = new ResourceBudget(resources, matcher, SubtypingPolicy.STRICT);

        var slot = new TypedSlot(0, TypedSlot.SlotKind.PARAM, "com.example.Mapper", "m");
        assertThat(budget.candidatesFor(slot)).hasSize(2);
    }

    @Test
    void producedValueMakesNewSlotCoverable() {
        var resources = new AvailableResources(List.of(), List.of(), null);
        var matcher = new SignatureMatcher(new TypeGraph());
        var budget = new ResourceBudget(resources, matcher, SubtypingPolicy.STRICT);

        var slot = new TypedSlot(0, TypedSlot.SlotKind.PARAM, "com.example.Owner", "owner");
        assertThat(budget.canCover(slot)).isFalse();

        var produced = new ProducedValue("s0", "$1", "com.example.Owner");
        var newBudget = budget.withProduced(produced);
        assertThat(newBudget.canCover(slot)).isTrue();
    }
}
