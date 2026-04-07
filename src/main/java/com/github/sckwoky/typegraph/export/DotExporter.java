package com.github.sckwoky.typegraph.export;

import com.github.sckwoky.typegraph.graph.TypeGraph;
import com.github.sckwoky.typegraph.model.*;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Exports a TypeGraph to GraphViz DOT format with color-coded edges.
 */
public class DotExporter {

    private static final Map<RelationshipKind, String> EDGE_COLORS = Map.of(
            RelationshipKind.IS, "#3498db",        // blue
            RelationshipKind.HAS, "#2ecc71",       // green
            RelationshipKind.CONSUMES, "#e67e22",   // orange
            RelationshipKind.PRODUCES, "#e74c3c"    // red
    );

    private static final Map<TypeKind, String> VERTEX_COLORS = Map.of(
            TypeKind.CLASS, "#d5e8d4",
            TypeKind.INTERFACE, "#dae8fc",
            TypeKind.ENUM, "#fff2cc",
            TypeKind.RECORD, "#e1d5e7",
            TypeKind.ANNOTATION, "#f8cecc",
            TypeKind.EXTERNAL, "#f5f5f5"
    );

    private static final Map<RelationshipKind, String> EDGE_STYLES = Map.of(
            RelationshipKind.IS, "bold",
            RelationshipKind.HAS, "solid",
            RelationshipKind.CONSUMES, "dashed",
            RelationshipKind.PRODUCES, "solid"
    );

    public void export(TypeGraph graph, Path outputFile) throws IOException {
        try (var writer = Files.newBufferedWriter(outputFile)) {
            export(graph, writer);
        }
    }

    public void export(TypeGraph graph, Writer writer) throws IOException {
        writer.write("digraph TypeGraph {\n");
        writer.write("  rankdir=LR;\n");
        writer.write("  node [shape=box, style=filled, fontname=\"Helvetica\"];\n");
        writer.write("  edge [fontname=\"Helvetica\", fontsize=10];\n\n");

        // Legend
        writer.write("  subgraph cluster_legend {\n");
        writer.write("    label=\"Legend\";\n");
        writer.write("    style=dashed;\n");
        writer.write("    legend_is [label=\"IS (extends/implements)\", fillcolor=\"#dae8fc\"];\n");
        writer.write("    legend_has [label=\"HAS (field)\", fillcolor=\"#d5e8d4\"];\n");
        writer.write("    legend_consumes [label=\"CONSUMES (param)\", fillcolor=\"#fff2cc\"];\n");
        writer.write("    legend_produces [label=\"PRODUCES (return)\", fillcolor=\"#f8cecc\"];\n");
        writer.write("  }\n\n");

        // Vertices
        for (var vertex : graph.vertices()) {
            var color = VERTEX_COLORS.getOrDefault(vertex.kind(), "#f5f5f5");
            writer.write("  \"%s\" [label=\"%s\", fillcolor=\"%s\", tooltip=\"%s\"];\n"
                    .formatted(escDot(vertex.fullyQualifiedName()), escDot(vertex.shortName()),
                            color, escDot(vertex.fullyQualifiedName())));
        }
        writer.write("\n");

        // Edges
        for (var edge : graph.edges()) {
            var src = graph.getEdgeSource(edge).fullyQualifiedName();
            var tgt = graph.getEdgeTarget(edge).fullyQualifiedName();
            var color = EDGE_COLORS.getOrDefault(edge.kind(), "#999999");
            var style = EDGE_STYLES.getOrDefault(edge.kind(), "solid");
            var label = edge.kind().name();
            if (edge.signature() != null) {
                label = edge.signature().methodName() + "()";
            }
            writer.write("  \"%s\" -> \"%s\" [label=\"%s\", color=\"%s\", style=%s];\n"
                    .formatted(escDot(src), escDot(tgt), escDot(label), color, style));
        }

        writer.write("}\n");
    }

    private static String escDot(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
