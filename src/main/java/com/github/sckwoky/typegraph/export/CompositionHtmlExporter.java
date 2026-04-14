package com.github.sckwoky.typegraph.export;

import com.github.sckwoky.typegraph.compose.GlobalCompositionGraph;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exports a {@link GlobalCompositionGraph} as a self-contained HTML page with an
 * interactive Cytoscape.js viewer. Lets the user inspect every TYPE, METHOD and
 * FIELD node, filter edge kinds, and click any node to see its full metadata.
 */
public class CompositionHtmlExporter {

    public void export(GlobalCompositionGraph graph, Path outputFile) throws IOException {
        export(graph, "[]", outputFile);
    }

    /**
     * Exports the graph along with a JSON array of chains (as produced by
     * {@link com.github.sckwoky.typegraph.compose.ChainVisualizationBuilder}).
     * If {@code chainsJson} is non-empty, the viewer renders a sidebar with
     * candidate chains and lets the user click any of them to highlight the
     * matching nodes and edges in the graph.
     */
    public void export(GlobalCompositionGraph graph, String chainsJson, Path outputFile) throws IOException {
        var jsonWriter = new StringWriter();
        new CompositionJsonExporter().export(graph, jsonWriter);
        var jsonData = jsonWriter.toString();
        var chainsPayload = (chainsJson == null || chainsJson.isBlank()) ? "[]" : chainsJson;

        var html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>Composition Graph Viewer</title>
                    <script src="https://unpkg.com/cytoscape@3.30.4/dist/cytoscape.min.js"></script>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
                        #cy { width: 100vw; height: 100vh; background: #fafafa; }
                        #controls {
                            position: absolute; top: 10px; left: 10px; z-index: 10;
                            background: rgba(255,255,255,0.97); padding: 14px; border-radius: 8px;
                            box-shadow: 0 2px 12px rgba(0,0,0,0.18); max-width: 320px;
                            max-height: 92vh; overflow-y: auto;
                        }
                        #controls h3 { margin-bottom: 8px; font-size: 14px; }
                        #controls h4 { margin: 10px 0 4px; font-size: 12px; color: #555; text-transform: uppercase; letter-spacing: 0.05em; }
                        #controls label { display: block; margin: 4px 0; font-size: 13px; cursor: pointer; }
                        #controls input[type=text] {
                            width: 100%%; padding: 6px; margin: 4px 0 8px; border: 1px solid #ccc;
                            border-radius: 4px; font-size: 13px;
                        }
                        .legend { margin-top: 8px; font-size: 12px; }
                        .legend-row { display: flex; align-items: center; margin: 3px 0; }
                        .legend-color { width: 22px; height: 4px; margin-right: 6px; border-radius: 2px; }
                        .legend-shape {
                            width: 14px; height: 14px; margin-right: 6px;
                            border: 1px solid #555;
                        }
                        .legend-shape.type-shape { background: #d5e8d4; border-radius: 4px; }
                        .legend-shape.method-shape { background: #fff2cc; transform: rotate(45deg); }
                        .legend-shape.field-shape { background: #dae8fc; border-radius: 50%%; }
                        #info {
                            position: absolute; bottom: 10px; right: 10px; z-index: 10;
                            background: rgba(255,255,255,0.97); padding: 12px; border-radius: 8px;
                            box-shadow: 0 2px 12px rgba(0,0,0,0.18); font-size: 12px; max-width: 480px;
                            max-height: 60vh; overflow-y: auto; display: none;
                            font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
                        }
                        #chains {
                            position: absolute; top: 10px; right: 10px; z-index: 10;
                            background: rgba(255,255,255,0.97); padding: 14px; border-radius: 8px;
                            box-shadow: 0 2px 12px rgba(0,0,0,0.18); font-size: 12px; width: 360px;
                            max-height: 65vh; overflow-y: auto;
                        }
                        #chains h3 { font-size: 14px; margin-bottom: 8px; }
                        #chains .empty { color: #888; font-style: italic; }
                        .chain-card {
                            border: 1px solid #ddd; border-radius: 6px; padding: 8px 10px;
                            margin: 6px 0; cursor: pointer; transition: background 0.15s;
                        }
                        .chain-card:hover { background: #f5faff; border-color: #79b6f0; }
                        .chain-card.active { background: #e8f3ff; border-color: #3498db; box-shadow: 0 0 0 2px rgba(52,152,219,0.25); }
                        .chain-card .header {
                            display: flex; justify-content: space-between; align-items: center;
                            margin-bottom: 4px;
                        }
                        .chain-card .label { font-weight: 600; }
                        .chain-card .conf {
                            font-size: 10px; padding: 2px 6px; border-radius: 4px;
                            color: white; font-weight: 600;
                        }
                        .chain-card .conf.HIGH { background: #2ecc71; }
                        .chain-card .conf.MEDIUM { background: #f39c12; }
                        .chain-card .conf.LOW { background: #95a5a6; }
                        .chain-card .score { font-size: 11px; color: #666; margin-left: 6px; }
                        .chain-card .step {
                            font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
                            font-size: 11px; margin: 2px 0; color: #444;
                        }
                        .chain-card .step .arrow { color: #999; margin: 0 4px; }
                        .chain-card .evidence { font-size: 10px; color: #888; margin-top: 4px; }
                        #chain-actions { margin-top: 6px; }
                        .kind-row {
                            display: flex; align-items: center; padding: 2px 4px;
                            border-radius: 3px; cursor: help; margin: 2px 0; position: relative;
                        }
                        .kind-row:hover { background: #eef4fc; }
                        .kind-row input { margin-right: 6px; cursor: pointer; }
                        .kind-row .kind-name { flex: 1; font-size: 12px; }
                        .kind-row .kind-code { font-size: 9px; color: #888; font-family: 'SF Mono', Monaco, monospace; margin-left: 4px; }
                        .edge-swatch { display: inline-block; width: 18px; height: 3px; margin-right: 6px; border-radius: 2px; vertical-align: middle; }
                        .node-swatch { display: inline-block; width: 10px; height: 10px; margin-right: 6px; border: 1px solid #666; border-radius: 2px; vertical-align: middle; }
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
                        #info b { font-family: -apple-system, BlinkMacSystemFont, sans-serif; }
                        #info .field-row { margin: 3px 0; }
                        #info .field-key { color: #666; }
                        button {
                            padding: 5px 12px; margin: 2px; border: 1px solid #ccc;
                            border-radius: 4px; background: #fff; cursor: pointer; font-size: 12px;
                        }
                        button:hover { background: #f0f0f0; }
                        button.active { background: #3498db; color: white; border-color: #2c80b4; }
                    </style>
                </head>
                <body>
                    <div id="controls">
                        <h3>Composition Graph</h3>
                        <input type="text" id="search" placeholder="Search nodes (type / method / field)…">

                        <h4>Node kinds</h4>
                        <div id="node-filters"></div>

                        <h4>Edge kinds</h4>
                        <div id="edge-filters"></div>

                        <h4>Layout</h4>
                        <div>
                            <button id="btn-cose" class="active" onclick="setLayout('cose')">Force</button>
                            <button id="btn-breadthfirst" onclick="setLayout('breadthfirst')">Hierarchy</button>
                            <button id="btn-concentric" onclick="setLayout('concentric')">Concentric</button>
                            <button onclick="cy.fit()">Fit</button>
                        </div>

                        <div class="legend">
                            <div style="font-size:10px;color:#666;margin-top:6px">Наведи курсор на любой пункт в списках выше — появится описание и пример.</div>
                        </div>
                    </div>
                    <div id="popover"></div>

                    <div id="chains">
                        <h3>Candidate chains</h3>
                        <div id="chain-list"><div class="empty">No chains supplied. Run with <code>--find-chains '...' -f html</code> to populate this panel.</div></div>
                        <div id="chain-actions" style="display:none">
                            <button onclick="clearChainHighlight()">Clear highlight</button>
                        </div>
                    </div>
                    <div id="info"></div>
                    <div id="cy"></div>

                    <script>
                        const graphData = %s;
                        const chainData = %s;
                        const ENUM_META = %s;
                        const edgeColors = {
                            CONSUMES: '#e67e22',
                            RECEIVER: '#9b59b6',
                            PRODUCES: '#e74c3c',
                            READS_FIELD: '#2ecc71',
                            EVIDENCE_CALLS: '#3498db'
                        };
                        const nodeShapes = { TYPE: 'roundrectangle', METHOD: 'diamond', FIELD: 'ellipse' };
                        const nodeColors = { TYPE: '#d5e8d4', METHOD: '#fff2cc', FIELD: '#dae8fc' };

                        const cy = cytoscape({
                            container: document.getElementById('cy'),
                            elements: graphData.elements,
                            style: [
                                { selector: 'node', style: {
                                    'label': 'data(label)',
                                    'text-valign': 'center',
                                    'text-halign': 'center',
                                    'background-color': function(ele) { return nodeColors[ele.data('kind')] || '#eee'; },
                                    'border-width': 1,
                                    'border-color': '#666',
                                    'font-size': '11px',
                                    'width': 'label',
                                    'height': 'label',
                                    'padding': '8px',
                                    'shape': function(ele) { return nodeShapes[ele.data('kind')] || 'roundrectangle'; }
                                }},
                                { selector: 'node[kind = "METHOD"]', style: {
                                    'shape': 'diamond',
                                    'width': 60,
                                    'height': 60,
                                    'text-margin-y': 4
                                }},
                                { selector: 'edge', style: {
                                    'width': 1.6,
                                    'target-arrow-shape': 'triangle',
                                    'curve-style': 'bezier',
                                    'line-color': function(ele) { return edgeColors[ele.data('kind')] || '#999'; },
                                    'target-arrow-color': function(ele) { return edgeColors[ele.data('kind')] || '#999'; },
                                    'label': 'data(label)',
                                    'font-size': '9px',
                                    'text-rotation': 'autorotate',
                                    'text-margin-y': -8,
                                    'text-background-color': '#fafafa',
                                    'text-background-opacity': 0.85,
                                    'text-background-padding': 1
                                }},
                                { selector: '.highlighted', style: { 'border-width': 3, 'border-color': '#e74c3c' }},
                                { selector: '.faded', style: { 'opacity': 0.10 }},
                                { selector: '.hidden', style: { 'display': 'none' }},
                                { selector: 'node.chain-node', style: {
                                    'border-width': 4, 'border-color': '#2980b9', 'background-blacken': -0.05
                                }},
                                { selector: 'edge.chain-edge', style: {
                                    'width': 4, 'line-color': '#2980b9', 'target-arrow-color': '#2980b9',
                                    'z-index': 999
                                }}
                            ],
                            layout: { name: 'cose', animate: false, nodeRepulsion: 9000, idealEdgeLength: 130, padding: 30 }
                        });

                        function setLayout(name) {
                            document.querySelectorAll('#controls button').forEach(b => b.classList.remove('active'));
                            const btn = document.getElementById('btn-' + name);
                            if (btn) btn.classList.add('active');
                            const opts = name === 'breadthfirst'
                                ? { name: 'breadthfirst', directed: true, padding: 20, spacingFactor: 1.2 }
                                : name === 'concentric'
                                    ? { name: 'concentric', padding: 20, minNodeSpacing: 30 }
                                    : { name: 'cose', animate: true, nodeRepulsion: 9000, idealEdgeLength: 130, padding: 30 };
                            cy.layout(opts).run();
                        }

                        // Search
                        document.getElementById('search').addEventListener('input', function(e) {
                            const q = e.target.value.toLowerCase();
                            cy.elements().removeClass('faded highlighted');
                            if (!q) return;
                            cy.nodes().forEach(n => {
                                const id = (n.data('id') || '').toLowerCase();
                                const label = (n.data('label') || '').toLowerCase();
                                const sig = (n.data('signature') || '').toLowerCase();
                                if (id.includes(q) || label.includes(q) || sig.includes(q)) {
                                    n.addClass('highlighted');
                                } else {
                                    n.addClass('faded');
                                }
                            });
                            cy.edges().addClass('faded');
                            cy.nodes('.highlighted').connectedEdges().removeClass('faded');
                        });

                        // Edge/node filter handlers are wired inside buildFilterRows()
                        // (below) since the checkboxes are built from ENUM_META.

                        // Click info
                        const infoDiv = document.getElementById('info');
                        function renderInfo(d) {
                            const skip = new Set(['id', 'label']);
                            let html = '<b>' + escapeHtml(d.label || d.id) + '</b> [' + d.kind + ']<br>';
                            for (const k of Object.keys(d)) {
                                if (skip.has(k) || d[k] == null || d[k] === '') continue;
                                if (k === 'kind') continue;
                                let v = d[k];
                                if (Array.isArray(v)) v = '[' + v.join(', ') + ']';
                                html += '<div class="field-row"><span class="field-key">' + k + ':</span> ' + escapeHtml(String(v)) + '</div>';
                            }
                            return html;
                        }
                        function escapeHtml(s) {
                            return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
                        }
                        cy.on('tap', 'node', function(evt) {
                            const n = evt.target;
                            const edges = n.connectedEdges();
                            let html = renderInfo(n.data());
                            html += '<div style="margin-top:6px"><b>Edges:</b> ' + edges.length + '</div>';
                            const counts = {};
                            edges.forEach(e => { counts[e.data('kind')] = (counts[e.data('kind')]||0) + 1; });
                            for (const [k,v] of Object.entries(counts)) {
                                html += '<div class="field-row"><span class="field-key">' + k + ':</span> ' + v + '</div>';
                            }
                            infoDiv.innerHTML = html;
                            infoDiv.style.display = 'block';
                        });
                        cy.on('tap', function(evt) { if (evt.target === cy) infoDiv.style.display = 'none'; });

                        // ─── Chain panel & highlighting ──────────────────────────────
                        function edgeFingerprint(e) {
                            const d = e.data();
                            return d.source + '|' + d.target + '|' + d.kind + '|' + (d.slotIndex == null ? -1 : d.slotIndex);
                        }

                        function clearChainHighlight() {
                            cy.elements().removeClass('faded chain-node chain-edge');
                            document.querySelectorAll('.chain-card').forEach(c => c.classList.remove('active'));
                            document.getElementById('chain-actions').style.display = 'none';
                        }

                        function highlightChain(chain, cardEl) {
                            const nodeIds = new Set(chain.highlight.nodeIds);
                            const edgeKeys = new Set(chain.highlight.edgeKeys);

                            cy.elements().addClass('faded').removeClass('chain-node chain-edge');
                            cy.nodes().forEach(n => {
                                if (nodeIds.has(n.data('id'))) {
                                    n.removeClass('faded');
                                    n.addClass('chain-node');
                                }
                            });
                            cy.edges().forEach(e => {
                                if (edgeKeys.has(edgeFingerprint(e))) {
                                    e.removeClass('faded');
                                    e.addClass('chain-edge');
                                }
                            });

                            document.querySelectorAll('.chain-card').forEach(c => c.classList.remove('active'));
                            if (cardEl) cardEl.classList.add('active');
                            document.getElementById('chain-actions').style.display = 'block';
                        }

                        // ─── Friendly-named filter rows with hover popover ──
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

                        function buildFilterRows() {
                            const nf = document.getElementById('node-filters');
                            ['TYPE', 'METHOD', 'FIELD'].forEach(k => {
                                const row = document.createElement('label');
                                row.className = 'kind-row';
                                row.innerHTML = '<input type="checkbox" class="node-filter" value="' + k + '" checked>' +
                                    '<span class="node-swatch" style="background:' + (nodeColors[k] || '#eee') + '"></span>' +
                                    '<span class="kind-name">' + escapeHtml(friendlyName('compositionNode', k)) + '</span>' +
                                    '<span class="kind-code">' + k + '</span>';
                                attachHover(row, 'compositionNode', k);
                                nf.appendChild(row);
                            });
                            const ef = document.getElementById('edge-filters');
                            ['CONSUMES', 'RECEIVER', 'PRODUCES', 'READS_FIELD', 'EVIDENCE_CALLS'].forEach(k => {
                                const row = document.createElement('label');
                                row.className = 'kind-row';
                                row.innerHTML = '<input type="checkbox" class="edge-filter" value="' + k + '" checked>' +
                                    '<span class="edge-swatch" style="background:' + (edgeColors[k] || '#999') + '"></span>' +
                                    '<span class="kind-name">' + escapeHtml(friendlyName('compositionEdge', k)) + '</span>' +
                                    '<span class="kind-code">' + k + '</span>';
                                attachHover(row, 'compositionEdge', k);
                                ef.appendChild(row);
                            });
                            // Re-wire change handlers (same behavior as inline version used to)
                            nf.addEventListener('change', updateNodeFilters);
                            ef.addEventListener('change', updateEdgeFilters);
                        }
                        function updateNodeFilters() {
                            const checked = Array.from(document.querySelectorAll('.node-filter:checked')).map(c => c.value);
                            cy.nodes().forEach(n => {
                                if (checked.includes(n.data('kind'))) n.removeClass('hidden');
                                else n.addClass('hidden');
                            });
                        }
                        function updateEdgeFilters() {
                            const checked = Array.from(document.querySelectorAll('.edge-filter:checked')).map(c => c.value);
                            cy.edges().forEach(e => {
                                if (checked.includes(e.data('kind'))) e.removeClass('hidden');
                                else e.addClass('hidden');
                            });
                        }
                        buildFilterRows();

                        function renderChainList() {
                            const list = document.getElementById('chain-list');
                            if (!Array.isArray(chainData) || chainData.length === 0) return;
                            list.innerHTML = '';
                            chainData.forEach(chain => {
                                const card = document.createElement('div');
                                card.className = 'chain-card';
                                let stepsHtml = '';
                                chain.steps.forEach((s, i) => {
                                    stepsHtml += '<div class="step">' + (i + 1) + '. ' +
                                        escapeHtml(s.displayCall) +
                                        '<span class="arrow">→</span>' +
                                        escapeHtml(s.producedType) + '</div>';
                                });
                                card.innerHTML =
                                    '<div class="header">' +
                                        '<span class="label">' + escapeHtml(chain.label) + '</span>' +
                                        '<span><span class="conf ' + chain.confidence + '">' + chain.confidence + '</span>' +
                                        '<span class="score">' + chain.score.toFixed(2) + '</span></span>' +
                                    '</div>' +
                                    stepsHtml +
                                    '<div class="evidence">' + escapeHtml(chain.evidenceSummary || '') + '</div>';
                                card.addEventListener('click', () => highlightChain(chain, card));
                                list.appendChild(card);
                            });
                        }
                        renderChainList();
                    </script>
                </body>
                </html>
                """.formatted(jsonData, chainsPayload, EnumDescriptions.toJs());

        Files.writeString(outputFile, html);
    }
}
