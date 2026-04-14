package com.github.sckwoky.typegraph.export;

import com.github.sckwoky.typegraph.compose.GlobalCompositionGraph;
import com.github.sckwoky.typegraph.compose.model.CompositionNode;
import com.github.sckwoky.typegraph.compose.model.CompositionNode.FieldNode;
import com.github.sckwoky.typegraph.compose.model.CompositionNode.MethodNode;
import com.github.sckwoky.typegraph.compose.model.CompositionNode.TypeNode;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Exports a {@link GlobalCompositionGraph} as Cytoscape.js-compatible JSON.
 */
public class CompositionJsonExporter {

    public void export(GlobalCompositionGraph graph, Path outputFile) throws IOException {
        try (var writer = Files.newBufferedWriter(outputFile)) {
            export(graph, writer);
        }
    }

    public void export(GlobalCompositionGraph graph, Writer writer) throws IOException {
        var sb = new StringBuilder();
        sb.append("{\n  \"elements\": {\n    \"nodes\": [\n");

        var nodes = graph.jgraphtGraph().vertexSet();
        int n = 0, total = nodes.size();
        for (var node : nodes) {
            sb.append("      ").append(renderNode(node));
            if (++n < total) sb.append(',');
            sb.append('\n');
        }
        sb.append("    ],\n    \"edges\": [\n");

        var edges = graph.jgraphtGraph().edgeSet();
        int e = 0, etot = edges.size();
        int eid = 0;
        for (var edge : edges) {
            var src = graph.jgraphtGraph().getEdgeSource(edge);
            var tgt = graph.jgraphtGraph().getEdgeTarget(edge);
            sb.append("      {\"data\": {")
                    .append("\"id\": ").append(jsonStr("e" + (eid++))).append(", ")
                    .append("\"source\": ").append(jsonStr(src.id())).append(", ")
                    .append("\"target\": ").append(jsonStr(tgt.id())).append(", ")
                    .append("\"kind\": ").append(jsonStr(edge.kind().name())).append(", ")
                    .append("\"label\": ").append(jsonStr(edgeLabel(edge.kind().name(), edge.slotIndex()))).append(", ")
                    .append("\"slotIndex\": ").append(edge.slotIndex()).append(", ")
                    .append("\"weight\": ").append(edge.weight())
                    .append("}}");
            if (++e < etot) sb.append(',');
            sb.append('\n');
        }
        sb.append("    ]\n  }\n}\n");

        writer.write(sb.toString());
    }

    private String renderNode(CompositionNode node) {
        var sb = new StringBuilder("{\"data\": {");
        sb.append("\"id\": ").append(jsonStr(node.id())).append(", ");
        sb.append("\"label\": ").append(jsonStr(node.displayLabel())).append(", ");
        sb.append("\"kind\": ").append(jsonStr(node.kind().name()));

        switch (node) {
            case TypeNode tn -> sb.append(", \"fqn\": ").append(jsonStr(tn.fqn()));
            case MethodNode mn -> {
                var op = mn.operator();
                sb.append(", \"signature\": ").append(jsonStr(op.signature().toString()));
                sb.append(", \"declaringType\": ").append(jsonStr(op.declaringType()));
                sb.append(", \"methodName\": ").append(jsonStr(op.signature().methodName()));
                sb.append(", \"returnType\": ").append(jsonStr(op.returnType()));
                sb.append(", \"paramTypes\": ").append(jsonArr(op.signature().parameterTypes()));
                sb.append(", \"isConstructor\": ").append(op.isConstructor());
                sb.append(", \"hasReceiver\": ").append(op.hasReceiver());
            }
            case FieldNode fn -> {
                sb.append(", \"declaringType\": ").append(jsonStr(fn.declaringType()));
                sb.append(", \"fieldName\": ").append(jsonStr(fn.fieldName()));
                sb.append(", \"fieldType\": ").append(jsonStr(fn.fieldType()));
            }
        }
        sb.append("}}");
        return sb.toString();
    }

    private static String edgeLabel(String kind, int slotIndex) {
        return slotIndex >= 0 ? kind + "[" + slotIndex + "]" : kind;
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String jsonArr(java.util.List<String> items) {
        return items.stream()
                .map(CompositionJsonExporter::jsonStr)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
