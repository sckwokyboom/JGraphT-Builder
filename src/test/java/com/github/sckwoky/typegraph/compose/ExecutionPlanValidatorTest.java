package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.*;
import com.github.sckwoky.typegraph.graph.TypeGraph;
import com.github.sckwoky.typegraph.model.MethodSignature;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionPlanValidatorTest {

    private final SignatureMatcher matcher = new SignatureMatcher(new TypeGraph());
    private final ExecutionPlanValidator validator =
            new ExecutionPlanValidator(matcher, SubtypingPolicy.STRICT);

    @Test
    void validatesSimpleConstructorChain() {
        var ctorSig = new MethodSignature(
                "com.example.Dog", "<init>",
                List.of("java.lang.String", "java.lang.Integer"),
                "com.example.Dog");
        var op = MethodOperator.fromSignature(ctorSig);

        var binding = new LinkedHashMap<TypedSlot, AvailableResource>();
        binding.put(op.paramSlots().get(0), new AvailableParam(0, "name", "java.lang.String"));
        binding.put(op.paramSlots().get(1), new AvailableParam(1, "age", "java.lang.Integer"));

        var produced = new ProducedValue("s0", "$1", "com.example.Dog");
        var step = new CandidateStep("s0", op, binding, SignatureMatchKind.EXACT, produced);

        var target = new TargetMethodSpec(
                "com.example.X", "create",
                List.of("java.lang.String", "java.lang.Integer"),
                List.of("name", "age"),
                List.of(),
                "com.example.Dog",
                false
        );
        var chain = new CandidateChainInternal(target, List.of(step),
                ChainScore.zero(), ChainConfidence.LOW, EvidenceTrace.empty(), new ValidationResult.Valid());

        var result = validator.validate(chain);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void rejectsChainWithUnboundSlot() {
        var ctorSig = new MethodSignature(
                "com.example.Dog", "<init>",
                List.of("java.lang.String", "java.lang.Integer"),
                "com.example.Dog");
        var op = MethodOperator.fromSignature(ctorSig);

        // Only bind first slot
        var binding = new LinkedHashMap<TypedSlot, AvailableResource>();
        binding.put(op.paramSlots().get(0), new AvailableParam(0, "name", "java.lang.String"));

        var step = new CandidateStep("s0", op, binding, SignatureMatchKind.EXACT,
                new ProducedValue("s0", "$1", "com.example.Dog"));

        var target = new TargetMethodSpec("com.example.X", "create",
                List.of(), List.of(), List.of(), "com.example.Dog", false);
        var chain = new CandidateChainInternal(target, List.of(step),
                ChainScore.zero(), ChainConfidence.LOW, EvidenceTrace.empty(), new ValidationResult.Valid());

        var result = validator.validate(chain);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void rejectsWrongFinalType() {
        var ctorSig = new MethodSignature(
                "com.example.Dog", "<init>",
                List.of("java.lang.String"),
                "com.example.Dog");
        var op = MethodOperator.fromSignature(ctorSig);
        var binding = new LinkedHashMap<TypedSlot, AvailableResource>();
        binding.put(op.paramSlots().get(0), new AvailableParam(0, "name", "java.lang.String"));
        var step = new CandidateStep("s0", op, binding, SignatureMatchKind.EXACT,
                new ProducedValue("s0", "$1", "com.example.Dog"));

        // Target wants Owner, but chain produces Dog
        var target = new TargetMethodSpec("com.example.X", "create",
                List.of("java.lang.String"), List.of("n"),
                List.of(), "com.example.Owner", false);
        var chain = new CandidateChainInternal(target, List.of(step),
                ChainScore.zero(), ChainConfidence.LOW, EvidenceTrace.empty(), new ValidationResult.Valid());

        var result = validator.validate(chain);
        assertThat(result.isValid()).isFalse();
    }
}
