package com.github.sckwoky.typegraph.export;

import com.github.sckwoky.typegraph.graph.TypeGraph;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exports a TypeGraph as a self-contained HTML file with an interactive Cytoscape.js visualization.
 * Can be opened directly in a browser — no server needed.
 */
public class HtmlExporter {

    public void export(TypeGraph graph, Path outputFile) throws IOException {
        // First, generate the JSON data
        var jsonWriter = new StringWriter();
        new JsonExporter().export(graph, jsonWriter);
        var jsonData = jsonWriter.toString();

        var html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>Type Graph Viewer</title>
                    <script src="https://unpkg.com/cytoscape@3.30.4/dist/cytoscape.min.js"></script>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
                        #cy { width: 100vw; height: 100vh; }
                        #controls {
                            position: absolute; top: 10px; left: 10px; z-index: 10;
                            background: rgba(255,255,255,0.95); padding: 12px; border-radius: 8px;
                            box-shadow: 0 2px 8px rgba(0,0,0,0.15); max-width: 300px;
                        }
                        #controls h3 { margin-bottom: 8px; font-size: 14px; }
                        #controls label { display: block; margin: 4px 0; font-size: 13px; cursor: pointer; }
                        #controls input[type=text] {
                            width: 100%%; padding: 6px; margin: 4px 0 8px; border: 1px solid #ccc;
                            border-radius: 4px; font-size: 13px;
                        }
                        .legend { margin-top: 8px; font-size: 12px; }
                        .legend-item { display: flex; align-items: center; margin: 2px 0; }
                        .legend-color { width: 20px; height: 3px; margin-right: 6px; }
                        #info {
                            position: absolute; bottom: 10px; left: 10px; z-index: 10;
                            background: rgba(255,255,255,0.95); padding: 10px; border-radius: 8px;
                            box-shadow: 0 2px 8px rgba(0,0,0,0.15); font-size: 12px; max-width: 400px;
                            display: none;
                        }
                        button {
                            padding: 4px 10px; margin: 2px; border: 1px solid #ccc;
                            border-radius: 4px; background: #fff; cursor: pointer; font-size: 12px;
                        }
                        button:hover { background: #f0f0f0; }
                    </style>
                </head>
                <body>
                    <div id="controls">
                        <h3>Type Graph</h3>
                        <input type="text" id="search" placeholder="Search types...">
                        <div>
                            <label><input type="checkbox" class="edge-filter" value="IS" checked> IS (extends/implements)</label>
                            <label><input type="checkbox" class="edge-filter" value="HAS" checked> HAS (fields)</label>
                            <label><input type="checkbox" class="edge-filter" value="CONSUMES" checked> CONSUMES (params)</label>
                            <label><input type="checkbox" class="edge-filter" value="PRODUCES" checked> PRODUCES (returns)</label>
                        </div>
                        <div style="margin-top:8px">
                            <button onclick="cy.layout({name:'cose',animate:true}).run()">Relayout</button>
                            <button onclick="cy.fit()">Fit</button>
                        </div>
                        <div class="legend">
                            <div class="legend-item"><div class="legend-color" style="background:#3498db;height:3px"></div>IS</div>
                            <div class="legend-item"><div class="legend-color" style="background:#2ecc71"></div>HAS</div>
                            <div class="legend-item"><div class="legend-color" style="background:#e67e22"></div>CONSUMES</div>
                            <div class="legend-item"><div class="legend-color" style="background:#e74c3c"></div>PRODUCES</div>
                        </div>
                    </div>
                    <div id="info"></div>
                    <div id="cy"></div>
                    <script>
                        const graphData = %s;
                        const edgeColors = { IS: '#3498db', HAS: '#2ecc71', CONSUMES: '#e67e22', PRODUCES: '#e74c3c' };
                        const nodeColors = { CLASS: '#d5e8d4', INTERFACE: '#dae8fc', ENUM: '#fff2cc', RECORD: '#e1d5e7', ANNOTATION: '#f8cecc', EXTERNAL: '#f5f5f5' };

                        const cy = cytoscape({
                            container: document.getElementById('cy'),
                            elements: graphData.elements,
                            style: [
                                { selector: 'node', style: {
                                    'label': 'data(label)', 'text-valign': 'center', 'text-halign': 'center',
                                    'background-color': function(ele) { return nodeColors[ele.data('kind')] || '#f5f5f5'; },
                                    'border-width': 1, 'border-color': '#999', 'font-size': '11px',
                                    'width': 'label', 'height': 'label', 'padding': '8px', 'shape': 'roundrectangle'
                                }},
                                { selector: 'edge', style: {
                                    'width': 2, 'target-arrow-shape': 'triangle', 'curve-style': 'bezier',
                                    'line-color': function(ele) { return edgeColors[ele.data('kind')] || '#999'; },
                                    'target-arrow-color': function(ele) { return edgeColors[ele.data('kind')] || '#999'; },
                                    'label': 'data(label)', 'font-size': '9px', 'text-rotation': 'autorotate',
                                    'text-margin-y': -10
                                }},
                                { selector: '.highlighted', style: { 'border-width': 3, 'border-color': '#e74c3c' }},
                                { selector: '.faded', style: { 'opacity': 0.15 }},
                                { selector: '.hidden', style: { 'display': 'none' }}
                            ],
                            layout: { name: 'cose', animate: false, nodeRepulsion: 8000, idealEdgeLength: 120 }
                        });

                        // Search
                        document.getElementById('search').addEventListener('input', function(e) {
                            const q = e.target.value.toLowerCase();
                            cy.elements().removeClass('faded highlighted');
                            if (!q) return;
                            cy.nodes().forEach(n => {
                                if (n.data('id').toLowerCase().includes(q) || n.data('label').toLowerCase().includes(q)) {
                                    n.addClass('highlighted');
                                } else {
                                    n.addClass('faded');
                                }
                            });
                            cy.edges().addClass('faded');
                            cy.nodes('.highlighted').connectedEdges().removeClass('faded');
                        });

                        // Edge filters
                        document.querySelectorAll('.edge-filter').forEach(cb => {
                            cb.addEventListener('change', function() {
                                const checked = Array.from(document.querySelectorAll('.edge-filter:checked')).map(c => c.value);
                                cy.edges().forEach(e => {
                                    if (checked.includes(e.data('kind'))) {
                                        e.removeClass('hidden');
                                    } else {
                                        e.addClass('hidden');
                                    }
                                });
                            });
                        });

                        // Click info
                        const infoDiv = document.getElementById('info');
                        cy.on('tap', 'node', function(evt) {
                            const n = evt.target;
                            const edges = n.connectedEdges();
                            let html = '<b>' + n.data('id') + '</b> [' + n.data('kind') + ']<br>';
                            html += 'Edges: ' + edges.length + '<br>';
                            const counts = {};
                            edges.forEach(e => { counts[e.data('kind')] = (counts[e.data('kind')]||0) + 1; });
                            for (const [k,v] of Object.entries(counts)) html += '  ' + k + ': ' + v + '<br>';
                            infoDiv.innerHTML = html;
                            infoDiv.style.display = 'block';
                        });
                        cy.on('tap', function(evt) { if (evt.target === cy) infoDiv.style.display = 'none'; });
                    </script>
                </body>
                </html>
                """.formatted(jsonData);

        Files.writeString(outputFile, html);
    }
}
