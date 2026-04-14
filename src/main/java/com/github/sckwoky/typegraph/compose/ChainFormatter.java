package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.*;

import java.util.List;

/**
 * Renders ranked candidate chains as either a human/LLM-readable text block
 * or a structured JSON document.
 */
public class ChainFormatter {

    private final PromptOutputBuilder promptBuilder = new PromptOutputBuilder();

    public String formatPrompt(TargetMethodSpec target, List<CandidateChainInternal> chains) {
        var sb = new StringBuilder();
        sb.append("Target: ").append(target.declaringType()).append('#').append(target.methodName())
                .append('(').append(String.join(", ", target.paramTypes())).append(')')
                .append(" -> ").append(target.returnType()).append('\n');
        sb.append("Available:\n");
        sb.append(renderAvailable(target.toAvailableResources()));

        if (chains.isEmpty()) {
            sb.append("\nNo candidate chains found.\n");
            return sb.toString();
        }

        for (int i = 0; i < chains.size(); i++) {
            var chain = chains.get(i);
            var prompt = promptBuilder.build(chain);
            sb.append('\n').append("Candidate ").append(i + 1)
                    .append(" [").append(prompt.confidenceLabel())
                    .append(", score=").append(String.format("%.2f", prompt.totalScore())).append("]\n");
            sb.append("  steps:\n");
            for (int j = 0; j < prompt.steps().size(); j++) {
                var step = prompt.steps().get(j);
                sb.append("    ").append(j + 1).append(". ").append(step.displayCall())
                        .append("  -> ").append(step.producedType()).append('\n');
            }
            sb.append("  notes: ").append(prompt.evidenceSummary()).append('\n');
        }
        return sb.toString();
    }

    public String formatJson(TargetMethodSpec target, List<CandidateChainInternal> chains) {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"target\": {\n");
        sb.append("    \"declaringType\": ").append(jsonStr(target.declaringType())).append(",\n");
        sb.append("    \"methodName\": ").append(jsonStr(target.methodName())).append(",\n");
        sb.append("    \"paramTypes\": ").append(jsonArr(target.paramTypes())).append(",\n");
        sb.append("    \"returnType\": ").append(jsonStr(target.returnType())).append(",\n");
        sb.append("    \"isStatic\": ").append(target.isStatic()).append('\n');
        sb.append("  },\n");
        sb.append("  \"candidates\": [\n");
        for (int i = 0; i < chains.size(); i++) {
            var chain = chains.get(i);
            sb.append("    {\n");
            sb.append("      \"confidence\": ").append(jsonStr(chain.confidence().name())).append(",\n");
            sb.append("      \"score\": {\n");
            sb.append("        \"total\": ").append(chain.score().total()).append(",\n");
            sb.append("        \"structural\": ").append(chain.score().structural()).append(",\n");
            sb.append("        \"reliability\": ").append(chain.score().reliability()).append(",\n");
            sb.append("        \"locality\": ").append(chain.score().locality()).append('\n');
            sb.append("      },\n");
            sb.append("      \"steps\": [\n");
            for (int j = 0; j < chain.steps().size(); j++) {
                var step = chain.steps().get(j);
                sb.append("        {\n");
                sb.append("          \"signature\": ").append(jsonStr(step.operator().signature().toString())).append(",\n");
                sb.append("          \"declaringType\": ").append(jsonStr(step.operator().declaringType())).append(",\n");
                sb.append("          \"methodName\": ").append(jsonStr(step.operator().signature().methodName())).append(",\n");
                sb.append("          \"producedType\": ").append(jsonStr(step.producedType())).append(",\n");
                sb.append("          \"binding\": [\n");
                int bIdx = 0;
                int bSize = step.binding().size();
                for (var entry : step.binding().entrySet()) {
                    sb.append("            { \"slot\": ").append(jsonStr(entry.getKey().slotKind() + "[" + entry.getKey().index() + "]"))
                            .append(", \"resource\": ").append(jsonStr(entry.getValue().displayName()))
                            .append(", \"type\": ").append(jsonStr(entry.getValue().typeFqn())).append(" }");
                    if (++bIdx < bSize) sb.append(',');
                    sb.append('\n');
                }
                sb.append("          ]\n");
                sb.append("        }");
                if (j < chain.steps().size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append("      ]\n");
            sb.append("    }");
            if (i < chains.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String renderAvailable(AvailableResources avail) {
        var sb = new StringBuilder();
        for (var p : avail.params()) {
            sb.append("  - param: ").append(p.name()).append(" (").append(p.typeFqn()).append(")\n");
        }
        for (var f : avail.fields()) {
            sb.append("  - field: this.").append(f.fieldName()).append(" (").append(f.typeFqn()).append(")\n");
        }
        if (avail.thisRef() != null) {
            sb.append("  - this:  ").append(avail.thisRef().typeFqn()).append('\n');
        }
        return sb.toString();
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String jsonArr(List<String> items) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(jsonStr(items.get(i)));
        }
        return sb.append(']').toString();
    }
}
