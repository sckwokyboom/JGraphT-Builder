package com.github.sckwoky.typegraph.export;

import com.github.sckwoky.typegraph.flow.BackwardSlicer;
import com.github.sckwoky.typegraph.flow.MethodFlowGraph;
import com.github.sckwoky.typegraph.flow.ProjectFlowGraphs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-method interactive flow-graph viewer.
 * <p>
 * Layout: writes {@code outputDir/index.html} plus one
 * {@code outputDir/flow_data/&lt;fileId&gt;.js} per method. Each data file uses
 * a JSONP-style {@code window.__flowReg(id, payload)} call so the viewer can
 * lazy-load methods on click without needing an HTTP server (works from
 * {@code file://}).
 * <p>
 * Features:
 * <ul>
 *   <li>Sidebar tree of all methods grouped by package and class, with kind counts</li>
 *   <li>Lazy loading via dynamic {@code <script>} tags</li>
 *   <li>Per-return backward slice highlighting (toggle + return selector)</li>
 *   <li>Click on a CALL node → jumps to the callee's flow graph (if present in the index)</li>
 *   <li>Search across the current method's nodes</li>
 *   <li>Filter by node kind / edge kind</li>
 * </ul>
 */
public class FlowHtmlExporter {

    private final FlowJsonExporter jsonExporter = new FlowJsonExporter();
    private final BackwardSlicer slicer = new BackwardSlicer();

    public void export(List<ProjectFlowGraphs.Entry> entries, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path dataDir = outputDir.resolve("flow_data");
        Files.createDirectories(dataDir);

        // Allocate fileIds and build the index
        var fileIds = new HashMap<String, String>();   // method signature → fileId
        var indexEntries = new ArrayList<IndexRow>();
        var sigToFileId = new HashMap<String, String>();

        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            String fileId = "m" + i;
            String sig = entry.graph().methodSignature().toString();
            fileIds.put(sig, fileId);
            sigToFileId.put(sig, fileId);
        }

        // Write per-method JSONP files and collect index rows with stats
        for (var entry : entries) {
            String sig = entry.graph().methodSignature().toString();
            String fileId = fileIds.get(sig);

            // Per-return slices, serialised as a map for the viewer
            var perReturn = slicer.slicePerReturn(entry.graph());
            var perReturnIds = new HashMap<String, List<String>>();
            for (var slice : perReturn.entrySet()) {
                var ids = new ArrayList<String>();
                for (var n : slice.getValue()) ids.add(n.id());
                perReturnIds.put(slice.getKey().id(), ids);
            }
            var allSliceIds = new ArrayList<String>();
            slicer.mergeSlices(perReturn).forEach(n -> allSliceIds.add(n.id()));

            String payload = jsonExporter.toJson(entry.graph(), allSliceIds);
            // Inject perReturn map into payload (re-render for richness)
            payload = injectPerReturn(payload, perReturnIds);

            String jsContent = "window.__flowReg(" + jsonStr(fileId) + ", " + payload + ");\n";
            Files.writeString(dataDir.resolve(fileId + ".js"), jsContent);

            indexEntries.add(new IndexRow(
                    fileId,
                    entry.declaringType(),
                    entry.methodName(),
                    entry.displayName(),
                    entry.packageName(),
                    sig,
                    entry.graph().nodeCount(),
                    entry.graph().edgeCount(),
                    entry.graph().branchNodes().size(),
                    entry.graph().loopNodes().size(),
                    entry.graph().callNodes().size(),
                    entry.graph().returnNodes().size()
            ));
        }

        // Index of (signature → fileId) for CALL navigation
        var callMapJson = renderSigToFileId(sigToFileId);
        var indexJson = renderIndex(indexEntries);

        var html = renderShell(indexJson, callMapJson);
        Files.writeString(outputDir.resolve("index.html"), html);
    }

    // ─── Index rendering ────────────────────────────────────────────────

    private record IndexRow(String fileId, String declaringType, String methodName,
                            String displayName, String packageName, String signature,
                            int nodes, int edges, int branches, int loops, int calls, int returns) {}

    private String renderIndex(List<IndexRow> rows) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(',');
            var r = rows.get(i);
            sb.append('{')
                    .append("\"fileId\":").append(jsonStr(r.fileId())).append(',')
                    .append("\"declaringType\":").append(jsonStr(r.declaringType())).append(',')
                    .append("\"methodName\":").append(jsonStr(r.methodName())).append(',')
                    .append("\"displayName\":").append(jsonStr(r.displayName())).append(',')
                    .append("\"package\":").append(jsonStr(r.packageName())).append(',')
                    .append("\"signature\":").append(jsonStr(r.signature())).append(',')
                    .append("\"stats\":{\"nodes\":").append(r.nodes())
                    .append(",\"edges\":").append(r.edges())
                    .append(",\"branches\":").append(r.branches())
                    .append(",\"loops\":").append(r.loops())
                    .append(",\"calls\":").append(r.calls())
                    .append(",\"returns\":").append(r.returns())
                    .append('}')
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private String renderSigToFileId(Map<String, String> sigToFileId) {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : sigToFileId.entrySet()) {
            if (!first) sb.append(',');
            sb.append(jsonStr(entry.getKey())).append(':').append(jsonStr(entry.getValue()));
            first = false;
        }
        return sb.append('}').toString();
    }

    private String injectPerReturn(String payload, Map<String, List<String>> perReturn) {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : perReturn.entrySet()) {
            if (!first) sb.append(',');
            sb.append(jsonStr(entry.getKey())).append(":[");
            boolean inner = true;
            for (var id : entry.getValue()) {
                if (!inner) sb.append(',');
                sb.append(jsonStr(id));
                inner = false;
            }
            sb.append(']');
            first = false;
        }
        sb.append('}');
        // Insert "perReturnSlices": <map> before the closing brace
        return payload.substring(0, payload.length() - 1) + ",\"perReturnSlices\":" + sb + "}";
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ─── HTML shell ─────────────────────────────────────────────────────

    private String renderShell(String indexJson, String sigToFileIdJson) {
        return SHELL_TEMPLATE.replace("__INDEX_JSON__", indexJson)
                .replace("__CALL_MAP_JSON__", sigToFileIdJson)
                .replace("__ENUM_META_JSON__", EnumDescriptions.toJs());
    }

    private static final String SHELL_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <title>Method Flow Graphs</title>
                <script src="https://unpkg.com/cytoscape@3.30.4/dist/cytoscape.min.js"></script>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; height: 100vh; overflow: hidden; }
                    #layout { display: flex; height: 100vh; }
                    .panel {
                        background: #f7f8fa; display: flex; flex-direction: column;
                        overflow: hidden; min-width: 0; flex-shrink: 0;
                        transition: width 0.2s, min-width 0.2s, padding 0.2s, border-width 0.2s;
                    }
                    .panel.collapsed { width: 0 !important; min-width: 0 !important; padding: 0; overflow: hidden; }
                    .panel.collapsed .panel-header,
                    .panel.collapsed .panel-body { display: none; }
                    .panel-header {
                        position: sticky; top: 0; z-index: 2; flex-shrink: 0;
                        background: #f7f8fa; padding: 10px 12px;
                        border-bottom: 1px solid #e4e7ed;
                        display: flex; align-items: center; gap: 8px;
                    }
                    .panel-header h2, .panel-header h3 { font-size: 14px; margin: 0; flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                    .panel-body { flex: 1; overflow-y: auto; padding: 8px 12px 12px; }
                    .panel-toggle {
                        width: 22px; height: 22px; border-radius: 4px;
                        border: 1px solid #c3c8d3; background: #fff; cursor: pointer;
                        font-size: 11px; line-height: 20px; text-align: center; color: #666;
                        flex-shrink: 0;
                    }
                    .panel-toggle:hover { background: #e8ecf2; }
                    .resize-handle {
                        width: 5px; cursor: col-resize; background: #d0d4dc; flex-shrink: 0;
                        transition: background 0.15s;
                    }
                    .resize-handle:hover, .resize-handle.active { background: #3498db; }
                    .reopen-btn {
                        position: absolute; z-index: 10;
                        width: 24px; height: 24px; border-radius: 5px;
                        border: 1px solid #c3c8d3; background: rgba(255,255,255,0.95);
                        cursor: pointer; font-size: 12px; line-height: 22px; text-align: center;
                        box-shadow: 0 1px 4px rgba(0,0,0,0.12); display: none; color: #555;
                    }
                    .reopen-btn:hover { background: #e8ecf2; }
                    #left-reopen { top: 8px; left: 8px; }
                    #right-reopen { top: 8px; right: 8px; }
                    #sidebar { width: 320px; border-right: 1px solid #d0d4dc; }
                    #sidebar.collapsed { border-right: none; }
                    #sidebar .panel-body { padding-top: 4px; }
                    #sidebar input[type=text] {
                        width: 100%; padding: 6px 8px; border: 1px solid #c3c8d3;
                        border-radius: 5px; font-size: 13px; margin-bottom: 6px;
                    }
                    .pkg { margin-top: 6px; }
                    .pkg-name {
                        font-weight: 600; color: #555; font-size: 11px; text-transform: uppercase;
                        letter-spacing: 0.05em; padding: 3px 0; cursor: pointer; user-select: none;
                    }
                    .pkg-name::before { content: '▼ '; font-size: 9px; }
                    .pkg.collapsed > .cls { display: none; }
                    .pkg.collapsed > .pkg-name::before { content: '▶ '; }
                    .cls { margin-left: 4px; margin-bottom: 3px; }
                    .cls-name {
                        font-weight: 600; color: #2c3e50; padding: 2px 0;
                        cursor: pointer; user-select: none; font-size: 13px;
                    }
                    .cls-name::before { content: '▾ '; font-size: 10px; color: #888; }
                    .cls.collapsed > .meth { display: none; }
                    .cls.collapsed > .cls-name::before { content: '▸ '; }
                    .meth {
                        display: flex; justify-content: space-between; align-items: center;
                        padding: 3px 6px; border-radius: 4px; cursor: pointer;
                        margin-left: 8px; font-family: 'SF Mono', Monaco, monospace; font-size: 11px;
                    }
                    .meth:hover { background: #e6ecf5; }
                    .meth.active { background: #3498db; color: white; }
                    .meth .badges { font-size: 9px; color: #777; font-family: -apple-system, sans-serif; }
                    .meth.active .badges { color: #cfe5fb; }
                    .badge { display: inline-block; padding: 1px 4px; border-radius: 3px; background: rgba(0,0,0,0.05); margin-left: 2px; }
                    .meth.active .badge { background: rgba(255,255,255,0.18); }
                    #main { position: relative; flex: 1; min-width: 100px; }
                    #cy { width: 100%; height: 100%; background: #fafbfc; }
                    #empty {
                        position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);
                        color: #999; font-size: 16px; text-align: center; pointer-events: none;
                    }
                    #right-panel { width: 340px; border-left: 1px solid #d0d4dc; }
                    #right-panel.collapsed { border-left: none; }
                    #right-panel h4 { font-size: 11px; margin: 10px 0 4px; color: #555; text-transform: uppercase; letter-spacing: 0.04em; }
                    #right-panel label { display: block; margin: 2px 0; cursor: pointer; font-size: 12px; }
                    #right-panel select, #right-panel input[type=text] {
                        width: 100%; padding: 4px 6px; border: 1px solid #ccc; border-radius: 4px; font-size: 12px;
                    }
                    #current-method {
                        font-size: 13px; font-weight: 600; color: #2c3e50;
                        padding: 4px 0 6px; word-break: break-all;
                    }
                    button {
                        padding: 4px 10px; border: 1px solid #c3c8d3; border-radius: 4px;
                        background: #fff; cursor: pointer; font-size: 12px; margin: 2px;
                    }
                    button:hover { background: #f0f4fa; }
                    button.active { background: #3498db; color: white; border-color: #2c80b4; }
                    #info-section { border-top: 1px solid #e4e7ed; margin-top: 10px; padding-top: 8px; }
                    #info-section h4 { margin-top: 0; }
                    #info { font-family: 'SF Mono', Monaco, monospace; font-size: 12px; }
                    #info b { font-family: -apple-system, sans-serif; }
                    #info .field-row { margin: 2px 0; }
                    #info .field-key { color: #666; }
                    #info .jump-link {
                        display: inline-block; margin-top: 6px; padding: 4px 8px;
                        background: #3498db; color: white; border-radius: 4px;
                        cursor: pointer; font-family: -apple-system, sans-serif;
                    }
                    .kind-row {
                        display: flex; align-items: center; padding: 2px 4px;
                        border-radius: 3px; cursor: help; position: relative;
                    }
                    .kind-row:hover { background: #eef4fc; }
                    .kind-row input { margin-right: 6px; cursor: pointer; }
                    .kind-row .kind-name { flex: 1; font-size: 12px; }
                    .kind-row .kind-code { font-size: 9px; color: #888; font-family: 'SF Mono', Monaco, monospace; margin-left: 4px; }
                    .edge-swatch { display: inline-block; width: 18px; height: 3px; margin-right: 6px; border-radius: 2px; vertical-align: middle; }
                    #popover {
                        position: fixed; display: none; z-index: 9999; pointer-events: none;
                        background: rgba(28,32,40,0.97); color: #f5f7fa;
                        padding: 10px 12px; border-radius: 6px; max-width: 380px;
                        font-size: 12px; line-height: 1.4; box-shadow: 0 6px 20px rgba(0,0,0,0.35);
                    }
                    #popover .pop-title { font-weight: 600; margin-bottom: 3px; font-size: 13px; }
                    #popover .pop-code { font-size: 10px; opacity: 0.65; font-family: 'SF Mono', Monaco, monospace; margin-left: 6px; font-weight: 400; }
                    #popover .pop-desc { margin-bottom: 5px; }
                    #popover .pop-example-label { font-size: 10px; opacity: 0.6; text-transform: uppercase; letter-spacing: 0.04em; margin-bottom: 2px; }
                    #popover .pop-example { font-family: 'SF Mono', Monaco, monospace; font-size: 11px; opacity: 0.92; white-space: pre-wrap; }
                </style>
            </head>
            <body>
                <div id="layout">
                    <aside id="sidebar" class="panel">
                        <div class="panel-header">
                            <h2>Methods</h2>
                            <button class="panel-toggle" id="left-collapse" title="Hide">◀</button>
                        </div>
                        <div class="panel-body">
                            <input type="text" id="sidebar-search" placeholder="Filter methods…">
                            <div id="method-tree"></div>
                        </div>
                    </aside>
                    <div class="resize-handle" id="left-handle"></div>
                    <main id="main">
                        <button class="reopen-btn" id="left-reopen" title="Show methods">▶</button>
                        <button class="reopen-btn" id="right-reopen" title="Show controls">◀</button>
                        <div id="empty">No method selected</div>
                        <div id="cy"></div>
                        <div id="popover"></div>
                    </main>
                    <div class="resize-handle" id="right-handle"></div>
                    <aside id="right-panel" class="panel">
                        <div class="panel-header">
                            <h3>Controls</h3>
                            <button class="panel-toggle" id="right-collapse" title="Hide">▶</button>
                        </div>
                        <div class="panel-body">
                            <div id="current-method">Pick a method</div>
                            <input type="text" id="node-search" placeholder="Search nodes…">
                            <h4>Slice mode</h4>
                            <label><input type="checkbox" id="slice-toggle"> Highlight backward slice</label>
                            <select id="return-picker" style="margin-top:4px"></select>
                            <h4>Layout</h4>
                            <button id="btn-dagre" class="active" onclick="setLayout('dagre')">Top-to-bottom</button>
                            <button id="btn-cose" onclick="setLayout('cose')">Force</button>
                            <button onclick="cy && cy.fit()">Fit</button>
                            <h4>Node kinds</h4>
                            <div id="node-filters"></div>
                            <h4>Edge kinds</h4>
                            <div id="edge-filters"></div>
                            <h4>Legend</h4>
                            <div id="legend"></div>
                            <div id="info-section">
                                <h4>Selected node</h4>
                                <div id="info">Click a node in the graph</div>
                            </div>
                        </div>
                    </aside>
                </div>
                <script>
                const INDEX = __INDEX_JSON__;
                const SIG_TO_FILE = __CALL_MAP_JSON__;
                const ENUM_META = __ENUM_META_JSON__;
                const loaded = {};
                let cy = null;
                let currentMethod = null;
                let sliceMode = false;

                const NODE_KINDS = ['PARAM','THIS_REF','FIELD_READ','FIELD_WRITE','LOCAL_DEF','LOCAL_USE','TEMP_EXPR','MERGE_VALUE','CALL','CALL_RESULT','RETURN','BRANCH','MERGE','LOOP','LITERAL'];
                const EDGE_KINDS = ['DATA_DEP','ARG_PASS','CALL_RESULT_OF','RETURN_DEP','DEF_USE','PHI_INPUT','CONTROL_DEP'];
                const NODE_COLORS = {
                    PARAM:'#a8d5a2', THIS_REF:'#c5e1a5', FIELD_READ:'#90caf9', FIELD_WRITE:'#64b5f6',
                    LOCAL_DEF:'#fff59d', LOCAL_USE:'#fff59d', TEMP_EXPR:'#eeeeee',
                    MERGE_VALUE:'#ce93d8', CALL:'#ffab91', CALL_RESULT:'#ffcc80',
                    RETURN:'#ef9a9a', BRANCH:'#b39ddb', MERGE:'#9fa8da', LOOP:'#80cbc4', LITERAL:'#f0f0f0'
                };
                const NODE_SHAPES = {
                    PARAM:'roundrectangle', THIS_REF:'roundrectangle',
                    FIELD_READ:'tag', FIELD_WRITE:'tag',
                    LOCAL_DEF:'roundrectangle', LOCAL_USE:'roundrectangle',
                    TEMP_EXPR:'rectangle', MERGE_VALUE:'hexagon',
                    CALL:'diamond', CALL_RESULT:'ellipse',
                    RETURN:'octagon', BRANCH:'diamond', MERGE:'hexagon',
                    LOOP:'hexagon', LITERAL:'rectangle'
                };
                const EDGE_COLORS = {
                    DATA_DEP:'#7f8c8d', ARG_PASS:'#e67e22', CALL_RESULT_OF:'#d35400',
                    RETURN_DEP:'#c0392b', DEF_USE:'#95a5a6', PHI_INPUT:'#8e44ad',
                    CONTROL_DEP:'#2980b9'
                };
                const EDGE_STYLE = {
                    CONTROL_DEP: 'dashed',
                    PHI_INPUT: 'dotted',
                    DEF_USE: 'dotted'
                };

                function escapeHtml(s) {
                    return String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
                }

                function buildSidebar() {
                    const tree = document.getElementById('method-tree');
                    const groups = {};
                    INDEX.forEach(row => {
                        const pkg = row.package || '(default)';
                        const cls = row.declaringType.split('.').pop();
                        groups[pkg] = groups[pkg] || {};
                        groups[pkg][cls] = groups[pkg][cls] || [];
                        groups[pkg][cls].push(row);
                    });
                    Object.keys(groups).sort().forEach(pkg => {
                        const pkgEl = document.createElement('div');
                        pkgEl.className = 'pkg';
                        pkgEl.innerHTML = '<div class="pkg-name">' + escapeHtml(pkg) + '</div>';
                        pkgEl.querySelector('.pkg-name').addEventListener('click', function() {
                            pkgEl.classList.toggle('collapsed');
                        });
                        Object.keys(groups[pkg]).sort().forEach(cls => {
                            const clsEl = document.createElement('div');
                            clsEl.className = 'cls';
                            clsEl.innerHTML = '<div class="cls-name">' + escapeHtml(cls) + '</div>';
                            clsEl.querySelector('.cls-name').addEventListener('click', function() {
                                clsEl.classList.toggle('collapsed');
                            });
                            groups[pkg][cls].forEach(row => {
                                const m = document.createElement('div');
                                m.className = 'meth';
                                m.dataset.fileId = row.fileId;
                                m.dataset.search = (row.displayName + ' ' + row.signature).toLowerCase();
                                const badges =
                                    (row.stats.branches > 0 ? '<span class="badge" title="Branches (if/switch/try/ternary): ' + row.stats.branches + '">B' + row.stats.branches + '</span>' : '') +
                                    (row.stats.loops > 0 ? '<span class="badge" title="Loops (for/foreach/while/do-while): ' + row.stats.loops + '">L' + row.stats.loops + '</span>' : '') +
                                    (row.stats.calls > 0 ? '<span class="badge" title="Calls (method/constructor invocations): ' + row.stats.calls + '">C' + row.stats.calls + '</span>' : '') +
                                    (row.stats.returns > 0 ? '<span class="badge" title="Returns: ' + row.stats.returns + '">R' + row.stats.returns + '</span>' : '');
                                m.innerHTML = '<span>' + escapeHtml(row.methodName) + '</span><span class="badges">' + badges + '</span>';
                                m.addEventListener('click', () => loadAndShow(row.fileId));
                                clsEl.appendChild(m);
                            });
                            pkgEl.appendChild(clsEl);
                        });
                        tree.appendChild(pkgEl);
                    });
                }

                document.getElementById('sidebar-search').addEventListener('input', e => {
                    const q = e.target.value.toLowerCase();
                    document.querySelectorAll('.meth').forEach(m => {
                        m.style.display = m.dataset.search.includes(q) ? '' : 'none';
                    });
                });

                window.__flowReg = function(fileId, payload) {
                    loaded[fileId] = payload;
                    if (currentMethod === fileId) renderGraph(payload);
                };

                function loadAndShow(fileId) {
                    currentMethod = fileId;
                    document.querySelectorAll('.meth').forEach(m =>
                        m.classList.toggle('active', m.dataset.fileId === fileId));
                    document.getElementById('empty').style.display = 'none';
                    if (loaded[fileId]) {
                        renderGraph(loaded[fileId]);
                        return;
                    }
                    const script = document.createElement('script');
                    script.src = 'flow_data/' + fileId + '.js';
                    script.onerror = () => {
                        alert('Failed to load ' + fileId + '.js — open this page via file:// from the same directory.');
                    };
                    document.head.appendChild(script);
                }

                function renderGraph(payload) {
                    document.getElementById('current-method').textContent = payload.id;
                    const elements = payload.elements;
                    if (cy) cy.destroy();
                    cy = cytoscape({
                        container: document.getElementById('cy'),
                        elements,
                        style: cyStyle(),
                        layout: dagreLayout()
                    });

                    cy.on('tap', 'node', evt => showInfo(evt.target));
                    cy.on('tap', evt => { /* panel stays open, close via button */ });

                    populateReturnPicker(payload);
                    applyFiltersAndSlice(payload);
                }

                function dagreLayout() {
                    return { name: 'breadthfirst', directed: true, spacingFactor: 1.1, padding: 20, animate: false };
                }

                function cyStyle() {
                    return [
                        { selector: 'node', style: {
                            'label': 'data(label)',
                            'background-color': ele => NODE_COLORS[ele.data('kind')] || '#eee',
                            'shape': ele => NODE_SHAPES[ele.data('kind')] || 'roundrectangle',
                            'border-width': 1, 'border-color': '#666',
                            'font-size': '10px', 'width': 'label', 'height': 'label',
                            'padding': '6px', 'text-valign': 'center', 'text-halign': 'center',
                            'text-wrap': 'wrap', 'text-max-width': 140
                        }},
                        { selector: 'edge', style: {
                            'width': 1.4,
                            'line-color': ele => EDGE_COLORS[ele.data('kind')] || '#999',
                            'target-arrow-color': ele => EDGE_COLORS[ele.data('kind')] || '#999',
                            'target-arrow-shape': 'triangle',
                            'curve-style': 'bezier',
                            'line-style': ele => EDGE_STYLE[ele.data('kind')] || 'solid',
                            'label': 'data(label)', 'font-size': '8px',
                            'text-rotation': 'autorotate', 'text-margin-y': -6,
                            'text-background-color': '#fafafa',
                            'text-background-opacity': 0.85,
                            'text-background-padding': 1
                        }},
                        { selector: '.faded', style: { 'opacity': 0.10 } },
                        { selector: '.hidden', style: { 'display': 'none' } },
                        { selector: 'node.in-slice', style: { 'border-width': 3, 'border-color': '#2980b9' } },
                        { selector: 'edge.in-slice', style: { 'width': 3, 'line-color': '#2980b9', 'target-arrow-color': '#2980b9', 'z-index': 999 } }
                    ];
                }

                function populateReturnPicker(payload) {
                    const sel = document.getElementById('return-picker');
                    sel.innerHTML = '';
                    if (!payload.perReturnSlices) return;
                    const ids = Object.keys(payload.perReturnSlices);
                    ids.forEach(rid => {
                        const opt = document.createElement('option');
                        opt.value = rid;
                        opt.textContent = rid;
                        sel.appendChild(opt);
                    });
                    if (ids.length === 0) {
                        const opt = document.createElement('option');
                        opt.textContent = '(no returns)';
                        sel.appendChild(opt);
                    }
                }

                function applyFiltersAndSlice(payload) {
                    if (!cy) return;
                    const nodeChecked = new Set(Array.from(document.querySelectorAll('.node-filter:checked')).map(c => c.value));
                    const edgeChecked = new Set(Array.from(document.querySelectorAll('.edge-filter:checked')).map(c => c.value));
                    cy.nodes().forEach(n => n.toggleClass('hidden', !nodeChecked.has(n.data('kind'))));
                    cy.edges().forEach(e => e.toggleClass('hidden', !edgeChecked.has(e.data('kind'))));

                    cy.elements().removeClass('faded in-slice');
                    if (sliceMode && payload.perReturnSlices) {
                        const sel = document.getElementById('return-picker').value;
                        const ids = new Set(payload.perReturnSlices[sel] || []);
                        cy.nodes().forEach(n => {
                            if (ids.has(n.data('id'))) n.addClass('in-slice');
                            else n.addClass('faded');
                        });
                        cy.edges().forEach(e => {
                            if (ids.has(e.data('source')) && ids.has(e.data('target'))) e.addClass('in-slice');
                            else e.addClass('faded');
                        });
                    }
                }

                function showInfo(node) {
                    const d = node.data();
                    const info = document.getElementById('info');
                    let html = '<b>' + escapeHtml(d.label || d.id) + '</b> [' + d.kind + ']<br>';
                    const skip = new Set(['id', 'label', 'kind']);
                    for (const k of Object.keys(d)) {
                        if (skip.has(k) || d[k] == null || d[k] === '') continue;
                        let v = d[k];
                        if (typeof v === 'object') v = JSON.stringify(v);
                        html += '<div class="field-row"><span class="field-key">' + k + ':</span> ' + escapeHtml(v) + '</div>';
                    }
                    if (d.kind === 'CALL' && d.callSignature && SIG_TO_FILE[d.callSignature]) {
                        const target = SIG_TO_FILE[d.callSignature];
                        html += '<div class="jump-link" onclick="loadAndShow(\\'' + target + '\\')">→ Jump to callee flow graph</div>';
                    }
                    info.innerHTML = html;
                    const rp = document.getElementById('right-panel');
                    if (rp.classList.contains('collapsed')) {
                        rp.classList.remove('collapsed');
                        document.getElementById('right-reopen').style.display = 'none';
                    }
                }

                function setLayout(name) {
                    document.querySelectorAll('#btn-dagre, #btn-cose').forEach(b => b.classList.remove('active'));
                    const btn = document.getElementById('btn-' + name);
                    if (btn) btn.classList.add('active');
                    if (!cy) return;
                    if (name === 'cose') cy.layout({name:'cose', animate:true, nodeRepulsion:9000, idealEdgeLength:80, padding:20}).run();
                    else cy.layout(dagreLayout()).run();
                }

                function metaFor(group, constant) {
                    return (ENUM_META[group] && ENUM_META[group][constant]) || null;
                }

                function friendlyName(group, constant) {
                    const m = metaFor(group, constant);
                    return m ? m.name : constant;
                }

                const popover = document.getElementById('popover');
                function showPopover(group, constant, e) {
                    const m = metaFor(group, constant);
                    if (!m) return;
                    popover.innerHTML =
                        '<div class="pop-title">' + escapeHtml(m.name) +
                            '<span class="pop-code">' + escapeHtml(constant) + '</span></div>' +
                        '<div class="pop-desc">' + escapeHtml(m.description) + '</div>' +
                        (m.example ? '<div class="pop-example-label">Пример</div><div class="pop-example">' + escapeHtml(m.example) + '</div>' : '');
                    popover.style.display = 'block';
                    positionPopover(e);
                }
                function positionPopover(e) {
                    const pad = 14;
                    const vw = window.innerWidth, vh = window.innerHeight;
                    const rect = popover.getBoundingClientRect();
                    let x = e.clientX + pad;
                    let y = e.clientY + pad;
                    if (x + rect.width > vw - 10) x = e.clientX - rect.width - pad;
                    if (y + rect.height > vh - 10) y = e.clientY - rect.height - pad;
                    popover.style.left = x + 'px';
                    popover.style.top = y + 'px';
                }
                function hidePopover() { popover.style.display = 'none'; }

                function attachHover(el, group, constant) {
                    el.addEventListener('mouseenter', e => showPopover(group, constant, e));
                    el.addEventListener('mousemove', e => positionPopover(e));
                    el.addEventListener('mouseleave', hidePopover);
                }

                function buildFilterCheckboxes() {
                    const nf = document.getElementById('node-filters');
                    NODE_KINDS.forEach(k => {
                        const row = document.createElement('label');
                        row.className = 'kind-row';
                        const colorBox = '<span style="display:inline-block;width:10px;height:10px;background:' + (NODE_COLORS[k] || '#eee') + ';border:1px solid #666;border-radius:2px;margin-right:6px;vertical-align:middle"></span>';
                        row.innerHTML = '<input type="checkbox" class="node-filter" value="' + k + '" checked>' +
                            colorBox +
                            '<span class="kind-name">' + escapeHtml(friendlyName('flowNode', k)) + '</span>' +
                            '<span class="kind-code">' + k + '</span>';
                        attachHover(row, 'flowNode', k);
                        nf.appendChild(row);
                    });
                    const ef = document.getElementById('edge-filters');
                    EDGE_KINDS.forEach(k => {
                        const row = document.createElement('label');
                        row.className = 'kind-row';
                        const swatch = '<span class="edge-swatch" style="background:' + (EDGE_COLORS[k] || '#999') + '"></span>';
                        row.innerHTML = '<input type="checkbox" class="edge-filter" value="' + k + '" checked>' +
                            swatch +
                            '<span class="kind-name">' + escapeHtml(friendlyName('flowEdge', k)) + '</span>' +
                            '<span class="kind-code">' + k + '</span>';
                        attachHover(row, 'flowEdge', k);
                        ef.appendChild(row);
                    });
                    nf.addEventListener('change', () => loaded[currentMethod] && applyFiltersAndSlice(loaded[currentMethod]));
                    ef.addEventListener('change', () => loaded[currentMethod] && applyFiltersAndSlice(loaded[currentMethod]));
                }

                function buildLegend() {
                    // Legend is now covered by the friendly-named filter rows;
                    // keep a tiny hint in the toolbar for discoverability.
                    const lg = document.getElementById('legend');
                    lg.innerHTML = '<div style="font-size:10px;color:#666">Наведи курсор на любой пункт в списках выше — появится описание и пример.</div>';
                }

                document.getElementById('slice-toggle').addEventListener('change', e => {
                    sliceMode = e.target.checked;
                    if (loaded[currentMethod]) applyFiltersAndSlice(loaded[currentMethod]);
                });
                document.getElementById('return-picker').addEventListener('change', () => {
                    if (loaded[currentMethod]) applyFiltersAndSlice(loaded[currentMethod]);
                });
                document.getElementById('node-search').addEventListener('input', e => {
                    if (!cy) return;
                    const q = e.target.value.toLowerCase();
                    cy.elements().removeClass('faded');
                    if (!q) return;
                    cy.nodes().forEach(n => {
                        const txt = ((n.data('label') || '') + ' ' + (n.data('id') || '') + ' ' + (n.data('callSignature') || '')).toLowerCase();
                        if (!txt.includes(q)) n.addClass('faded');
                    });
                });

                buildSidebar();
                buildFilterCheckboxes();
                buildLegend();

                /* ── Panel collapse/expand ── */
                function setupPanel(panel, collapseBtn, reopenBtn) {
                    collapseBtn.addEventListener('click', () => {
                        panel.classList.add('collapsed');
                        reopenBtn.style.display = 'block';
                    });
                    reopenBtn.addEventListener('click', () => {
                        panel.classList.remove('collapsed');
                        reopenBtn.style.display = 'none';
                    });
                }
                setupPanel(document.getElementById('sidebar'),
                           document.getElementById('left-collapse'),
                           document.getElementById('left-reopen'));
                setupPanel(document.getElementById('right-panel'),
                           document.getElementById('right-collapse'),
                           document.getElementById('right-reopen'));

                /* ── Resize handles ── */
                function setupResize(handle, panel, side) {
                    let dragging = false, startX, startW;
                    handle.addEventListener('mousedown', e => {
                        dragging = true; startX = e.clientX;
                        startW = panel.offsetWidth;
                        handle.classList.add('active');
                        document.body.style.cursor = 'col-resize';
                        document.body.style.userSelect = 'none';
                        e.preventDefault();
                    });
                    document.addEventListener('mousemove', e => {
                        if (!dragging) return;
                        const dx = side === 'left' ? e.clientX - startX : startX - e.clientX;
                        const w = Math.max(180, Math.min(startW + dx, window.innerWidth * 0.45));
                        panel.style.width = w + 'px';
                    });
                    document.addEventListener('mouseup', () => {
                        if (!dragging) return;
                        dragging = false;
                        handle.classList.remove('active');
                        document.body.style.cursor = '';
                        document.body.style.userSelect = '';
                    });
                }
                setupResize(document.getElementById('left-handle'),
                            document.getElementById('sidebar'), 'left');
                setupResize(document.getElementById('right-handle'),
                            document.getElementById('right-panel'), 'right');
                </script>
            </body>
            </html>
            """;
}
