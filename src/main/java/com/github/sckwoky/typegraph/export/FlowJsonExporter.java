package com.github.sckwoky.typegraph.export;

import com.github.sckwoky.typegraph.flow.MethodFlowGraph;
import com.github.sckwoky.typegraph.flow.model.FlowEdge;
import com.github.sckwoky.typegraph.flow.model.FlowNode;
import com.github.sckwoky.typegraph.flow.model.FlowNodeKind;

import java.util.List;
import java.util.Set;

/**
 * Renders a {@link MethodFlowGraph} as a JSON document compatible with Cytoscape.js.
 * Used both stand-alone (one file per method) and as input to {@link FlowHtmlExporter}
 * which embeds it via JSONP-style script tags.
 */
public class FlowJsonExporter {

    /**
     * Returns a Cytoscape elements payload for the graph, optionally with a list of
     * "slice" node ids that the viewer can use to highlight a backward slice.
     */
    public String toJson(MethodFlowGraph graph, List<String> sliceNodeIds) {
        var sb = new StringBuilder();
        sb.append('{');
        sb.append("\"id\": ").append(jsonStr(graph.methodSignature().toString())).append(',');
        sb.append("\"stats\": ").append(renderStats(graph)).append(',');
        sb.append("\"sliceNodeIds\": ").append(jsonStrArr(sliceNodeIds)).append(',');
        sb.append("\"elements\": {");
        sb.append("\"nodes\": [");
        boolean first = true;
        for (var n : graph.nodes()) {
            if (!first) sb.append(',');
            sb.append(renderNode(n));
            first = false;
        }
        sb.append("],\"edges\": [");
        first = true;
        int eid = 0;
        for (var e : graph.edges()) {
            if (!first) sb.append(',');
            sb.append(renderEdge(graph, e, "fe" + (eid++)));
            first = false;
        }
        sb.append("]}");
        sb.append('}');
        return sb.toString();
    }

    private String renderNode(FlowNode n) {
        var sb = new StringBuilder();
        sb.append("{\"data\":{");
        sb.append("\"id\":").append(jsonStr(n.id())).append(',');
        sb.append("\"label\":").append(jsonStr(n.label())).append(',');
        sb.append("\"kind\":").append(jsonStr(n.kind().name())).append(',');
        sb.append("\"controlSubtype\":").append(jsonStr(n.controlSubtype().name()));
        if (n.typeFqn() != null) sb.append(",\"typeFqn\":").append(jsonStr(n.typeFqn()));
        if (n.variableName() != null) sb.append(",\"variableName\":").append(jsonStr(n.variableName()));
        if (n.variableVersion() >= 0) sb.append(",\"variableVersion\":").append(n.variableVersion());
        if (n.callSignature() != null) sb.append(",\"callSignature\":").append(jsonStr(n.callSignature().toString()));
        if (n.callResolution() != null) sb.append(",\"callResolution\":").append(jsonStr(n.callResolution().name()));
        if (n.fieldOrigin() != null) sb.append(",\"fieldOrigin\":").append(jsonStr(n.fieldOrigin().name()));
        if (n.sourceLine() > 0) sb.append(",\"line\":").append(n.sourceLine());
        if (n.enclosingControlId() != null) sb.append(",\"enclosingControlId\":").append(jsonStr(n.enclosingControlId()));
        if (n.stmtOrdinal() >= 0) sb.append(",\"stmtOrdinal\":").append(n.stmtOrdinal());
        if (n.attributes() != null) {
            for (var entry : n.attributes().entrySet()) {
                sb.append(',').append(jsonStr(entry.getKey())).append(':').append(jsonStr(entry.getValue()));
            }
        }
        sb.append("}}");
        return sb.toString();
    }

    private String renderEdge(MethodFlowGraph graph, FlowEdge e, String id) {
        var sb = new StringBuilder();
        sb.append("{\"data\":{");
        sb.append("\"id\":").append(jsonStr(id)).append(',');
        sb.append("\"source\":").append(jsonStr(graph.getEdgeSource(e).id())).append(',');
        sb.append("\"target\":").append(jsonStr(graph.getEdgeTarget(e).id())).append(',');
        sb.append("\"kind\":").append(jsonStr(e.kind().name())).append(',');
        sb.append("\"label\":").append(jsonStr(e.label()));
        sb.append("}}");
        return sb.toString();
    }

    private String renderStats(MethodFlowGraph graph) {
        var counts = graph.kindCounts();
        var sb = new StringBuilder();
        sb.append("{\"nodes\":").append(graph.nodeCount());
        sb.append(",\"edges\":").append(graph.edgeCount());
        for (var kind : FlowNodeKind.values()) {
            sb.append(',').append(jsonStr(kind.name())).append(':').append(counts.getOrDefault(kind, 0));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String jsonStrArr(List<String> items) {
        var sb = new StringBuilder("[");
        boolean first = true;
        for (var s : items) {
            if (!first) sb.append(',');
            sb.append(jsonStr(s));
            first = false;
        }
        return sb.append(']').toString();
    }

    public static String jsonStrArrSet(Set<String> items) {
        var sb = new StringBuilder("[");
        boolean first = true;
        for (var s : items) {
            if (!first) sb.append(',');
            sb.append("\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            first = false;
        }
        return sb.append(']').toString();
    }
}
