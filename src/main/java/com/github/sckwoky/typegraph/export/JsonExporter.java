package com.github.sckwoky.typegraph.export;

import com.github.sckwoky.typegraph.graph.TypeGraph;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exports a TypeGraph to JSON format compatible with Cytoscape.js.
 */
public class JsonExporter {

    public void export(TypeGraph graph, Path outputFile) throws IOException {
        try (var writer = Files.newBufferedWriter(outputFile)) {
            export(graph, writer);
        }
    }

    public void export(TypeGraph graph, Writer writer) throws IOException {
        writer.write("{\n  \"elements\": {\n    \"nodes\": [\n");

        var vertices = graph.vertices().stream().toList();
        for (int i = 0; i < vertices.size(); i++) {
            var v = vertices.get(i);
            writer.write("      { \"data\": { \"id\": \"%s\", \"label\": \"%s\", \"kind\": \"%s\" } }"
                    .formatted(escJson(v.fullyQualifiedName()), escJson(v.shortName()), v.kind().name()));
            if (i < vertices.size() - 1) writer.write(",");
            writer.write("\n");
        }

        writer.write("    ],\n    \"edges\": [\n");

        var edges = graph.edges().stream().toList();
        for (int i = 0; i < edges.size(); i++) {
            var e = edges.get(i);
            var src = graph.getEdgeSource(e).fullyQualifiedName();
            var tgt = graph.getEdgeTarget(e).fullyQualifiedName();
            var label = e.kind().name();
            if (e.signature() != null) {
                label = e.signature().methodName() + "()";
            }
            writer.write("      { \"data\": { \"id\": \"e%d\", \"source\": \"%s\", \"target\": \"%s\", \"kind\": \"%s\", \"label\": \"%s\" } }"
                    .formatted(i, escJson(src), escJson(tgt), e.kind().name(), escJson(label)));
            if (i < edges.size() - 1) writer.write(",");
            writer.write("\n");
        }

        writer.write("    ]\n  }\n}\n");
    }

    private static String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
