package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.*;

import java.util.*;

/**
 * Resource-aware bounded search for candidate call chains.
 * <p>
 * Strategy: pick a "final producer" (a method whose return type matches the target's
 * return type), then recursively try to satisfy its uncovered slots by introducing
 * "enabler" methods earlier in the chain. The {@link ResourceBudget} ensures every
 * step is operationally applicable from already-available or already-produced values.
 */
public class ChainSearchEngine {

    private final SignatureMatcher matcher;

    public ChainSearchEngine(SignatureMatcher matcher) {
        this.matcher = matcher;
    }

    public record SearchOptions(
            int maxDepth,
            int topK,
            SubtypingPolicy subtypingPolicy,
            boolean preferLocalMethods
    ) {
        public static SearchOptions defaults() {
            return new SearchOptions(4, 50, SubtypingPolicy.ALLOW_SUBTYPE, true);
        }
    }

    public List<CandidateChainInternal> search(
            TargetMethodSpec target,
            GlobalCompositionGraph graph,
            SearchOptions options
    ) {
        var initialBudget = new ResourceBudget(target.toAvailableResources(), matcher, options.subtypingPolicy());
        var collector = new ArrayList<CandidateChainInternal>();

        var producers = graph.methodsProducing(target.returnType());
        // If subtyping allowed, also consider methods producing a subtype of target.returnType
        if (options.subtypingPolicy() != SubtypingPolicy.STRICT) {
            for (var m : graph.allMethods()) {
                if (producers.contains(m)) continue;
                var kind = matcher.match(m.operator().returnType(), target.returnType(), options.subtypingPolicy());
                if (matcher.isAcceptable(kind) && kind != SignatureMatchKind.EXACT) {
                    producers = new ArrayList<>(producers);
                    ((ArrayList<CompositionNode.MethodNode>) producers).add(m);
                }
            }
        }

        for (var producer : producers) {
            // Skip the target method itself if it appears in the graph
            if (isTargetMethod(producer.operator(), target)) continue;

            enumeratePartialChains(
                    producer,
                    initialBudget,
                    options.maxDepth(),
                    new ArrayList<>(),
                    target,
                    graph,
                    options,
                    collector,
                    new HashSet<>()
            );
            if (collector.size() >= options.topK()) break;
        }

        return collector;
    }

    private void enumeratePartialChains(
            CompositionNode.MethodNode finalProducer,
            ResourceBudget budget,
            int remainingDepth,
            List<CandidateStep> partialChain,
            TargetMethodSpec target,
            GlobalCompositionGraph graph,
            SearchOptions options,
            List<CandidateChainInternal> collector,
            Set<String> usedSignatures
    ) {
        if (collector.size() >= options.topK()) return;

        // Check if we can directly bind all slots of the final producer
        var op = finalProducer.operator();
        var binding = tryBindAll(op, budget, options.subtypingPolicy());
        if (binding != null) {
            String stepId = "s" + partialChain.size();
            String valueId = "$" + (partialChain.size() + 1);
            var produced = new ProducedValue(stepId, valueId, op.returnType());
            var step = new CandidateStep(stepId, op, binding, dominantMatchKind(binding, op),
                    produced);
            var fullChain = new ArrayList<>(partialChain);
            fullChain.add(step);
            collector.add(new CandidateChainInternal(
                    target, fullChain, ChainScore.zero(), ChainConfidence.LOW,
                    EvidenceTrace.empty(), new ValidationResult.Valid()));
            return;
        }

        if (remainingDepth <= 0) return;

        // Find uncovered slots and try to satisfy each via an enabler method
        var uncovered = budget.uncovered(op);
        if (uncovered.isEmpty()) return;

        // Pick the first uncovered slot and enumerate enablers for it
        var slot = uncovered.iterator().next();
        var enablers = graph.methodsProducing(slot.typeFqn());

        for (var enabler : enablers) {
            if (collector.size() >= options.topK()) return;
            var enablerOp = enabler.operator();
            if (usedSignatures.contains(enablerOp.signature().toString())) continue;
            if (isTargetMethod(enablerOp, target)) continue;

            // The enabler must itself be applicable from the current budget
            var enablerBinding = tryBindAll(enablerOp, budget, options.subtypingPolicy());
            if (enablerBinding == null) continue;

            String enablerStepId = "s" + partialChain.size();
            String enablerValueId = "$" + (partialChain.size() + 1);
            var enablerProduced = new ProducedValue(enablerStepId, enablerValueId, enablerOp.returnType());
            var enablerStep = new CandidateStep(
                    enablerStepId, enablerOp, enablerBinding,
                    dominantMatchKind(enablerBinding, enablerOp), enablerProduced);

            var newPartial = new ArrayList<>(partialChain);
            newPartial.add(enablerStep);
            var newBudget = budget.withProduced(enablerProduced);
            var newUsed = new HashSet<>(usedSignatures);
            newUsed.add(enablerOp.signature().toString());

            enumeratePartialChains(
                    finalProducer, newBudget, remainingDepth - 1, newPartial,
                    target, graph, options, collector, newUsed);
        }
    }

    /**
     * Attempts to bind every slot of {@code op} from the budget. Returns null if any
     * slot cannot be covered.
     */
    private Map<TypedSlot, AvailableResource> tryBindAll(MethodOperator op, ResourceBudget budget, SubtypingPolicy policy) {
        var binding = new LinkedHashMap<TypedSlot, AvailableResource>();
        for (var slot : op.allSlots()) {
            var candidates = budget.candidatesFor(slot);
            if (candidates.isEmpty()) return null;
            // Prefer produced values for inner slots (pipe-style); but for receiver of an
            // instance method on a field type, prefer the AvailableField/this.
            AvailableResource chosen = pickBest(candidates, slot, op);
            binding.put(slot, chosen);
        }
        return binding;
    }

    private AvailableResource pickBest(List<AvailableResource> candidates, TypedSlot slot, MethodOperator op) {
        // For receiver: prefer AvailableThis/AvailableField over produced values
        if (slot.isReceiver()) {
            for (var c : candidates) if (c instanceof AvailableThis) return c;
            for (var c : candidates) if (c instanceof AvailableField) return c;
            for (var c : candidates) if (c instanceof AvailableParam) return c;
            return candidates.get(0);
        }
        // For params: prefer produced values, then params, then fields, then this
        for (var c : candidates) if (c instanceof ProducedValue) return c;
        for (var c : candidates) if (c instanceof AvailableParam) return c;
        for (var c : candidates) if (c instanceof AvailableField) return c;
        return candidates.get(0);
    }

    private SignatureMatchKind dominantMatchKind(Map<TypedSlot, AvailableResource> binding, MethodOperator op) {
        // Aggregate the worst match kind across all slots — used for reliability scoring
        SignatureMatchKind worst = SignatureMatchKind.EXACT;
        for (var entry : binding.entrySet()) {
            var slot = entry.getKey();
            var res = entry.getValue();
            // Use a strict subtyping check here, regardless of the search policy,
            // so the score reflects how exact each binding is.
            if (res.typeFqn().equals(slot.typeFqn())) continue;
            // Otherwise, downgrade
            if (worst == SignatureMatchKind.EXACT) worst = SignatureMatchKind.SUBTYPE_COMPATIBLE;
        }
        return worst;
    }

    private boolean isTargetMethod(MethodOperator op, TargetMethodSpec target) {
        return op.declaringType().equals(target.declaringType())
                && op.signature().methodName().equals(target.methodName())
                && op.signature().parameterTypes().equals(target.paramTypes());
    }
}
