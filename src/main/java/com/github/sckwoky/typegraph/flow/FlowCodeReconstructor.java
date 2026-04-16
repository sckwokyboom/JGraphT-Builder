package com.github.sckwoky.typegraph.flow;

import com.github.sckwoky.typegraph.flow.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Reconstructs Java source code from a {@link MethodFlowGraph}.
 * <p>
 * Uses a two-pass algorithm:
 * <ol>
 *   <li>Collect "statement root" nodes grouped by enclosing control id.</li>
 *   <li>For each statement root, recursively render by walking the expression sub-tree.</li>
 * </ol>
 * The output is parseable Java, though not necessarily identical to the original source.
 */
public class FlowCodeReconstructor {

    private final MethodFlowGraph graph;
    private final Set<String> visited = new HashSet<>();
    /** Nodes that are consumed as LOOP_INIT or LOOP_UPDATE — should not appear at top level. */
    private final Set<FlowNode> loopOwnedNodes = new HashSet<>();

    public FlowCodeReconstructor(MethodFlowGraph graph) {
        this.graph = graph;
        collectLoopOwnedNodes();
    }

    /** Pre-compute nodes that are referenced via LOOP_INIT or LOOP_UPDATE edges. */
    private void collectLoopOwnedNodes() {
        for (var edge : graph.edges()) {
            if (edge.kind() == FlowEdgeKind.LOOP_INIT || edge.kind() == FlowEdgeKind.LOOP_UPDATE) {
                loopOwnedNodes.add(graph.getEdgeSource(edge));
            }
        }
    }

    /**
     * Reconstruct the method source (signature + body).
     */
    public String reconstruct() {
        var sig = graph.methodSignature();
        var sb = new StringBuilder();

        // Build signature
        String returnType = sig.returnType() == null ? "void" : shortName(sig.returnType());
        String methodName = sig.methodName();

        // Build parameter list from PARAM nodes
        var params = graph.paramNodes().stream()
                .sorted(Comparator.comparingInt(FlowNode::stmtOrdinal)
                        .thenComparingInt(FlowNode::sourceLine))
                .toList();

        sb.append(returnType).append(" ").append(methodName).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            var p = params.get(i);
            sb.append(shortName(p.typeFqn() != null ? p.typeFqn() : "Object"));
            sb.append(" ").append(p.variableName());
        }
        sb.append(") {\n");

        // Render body — top-level statements (enclosingControlId == null)
        var body = renderChildBlock(null);
        sb.append(body);

        sb.append("}\n");
        return sb.toString();
    }

    // ─── Pass 1: Collect statement roots ───────────────────────────────

    private static final Set<FlowNodeKind> STATEMENT_ROOT_KINDS = EnumSet.of(
            FlowNodeKind.LOCAL_DEF, FlowNodeKind.CALL, FlowNodeKind.RETURN,
            FlowNodeKind.BRANCH, FlowNodeKind.LOOP, FlowNodeKind.FIELD_WRITE,
            FlowNodeKind.THROW, FlowNodeKind.BREAK, FlowNodeKind.CONTINUE,
            FlowNodeKind.ASSERT, FlowNodeKind.SYNCHRONIZED, FlowNodeKind.YIELD,
            FlowNodeKind.OBJECT_CREATE
    );

    /**
     * Collect statement root nodes for a given enclosing control id (null = top-level).
     */
    private List<FlowNode> collectStatementRoots(String enclosingControlId) {
        var assignTargetNodes = collectAssignTargetNodes();

        return graph.nodes().stream()
                .filter(n -> STATEMENT_ROOT_KINDS.contains(n.kind()))
                .filter(n -> Objects.equals(n.enclosingControlId(), enclosingControlId))
                .filter(n -> !assignTargetNodes.contains(n))
                .filter(n -> !loopOwnedNodes.contains(n))
                .filter(n -> {
                    // For CALL nodes: only standalone calls (result not consumed)
                    if (n.kind() == FlowNodeKind.CALL) {
                        return isStandaloneCall(n);
                    }
                    // For OBJECT_CREATE nodes: only standalone creations (result not consumed)
                    if (n.kind() == FlowNodeKind.OBJECT_CREATE) {
                        return isStandaloneCall(n);
                    }
                    return true;
                })
                .sorted(Comparator.comparingInt(FlowNode::stmtOrdinal)
                        .thenComparingInt(FlowNode::sourceLine))
                .collect(Collectors.toList());
    }

    private Set<FlowNode> collectAssignTargetNodes() {
        return graph.edges().stream()
                .filter(e -> e.kind() == FlowEdgeKind.ASSIGN_TARGET)
                .map(graph::getEdgeTarget)
                .collect(Collectors.toSet());
    }

    /**
     * A CALL/OBJECT_CREATE is standalone if its CALL_RESULT node has no outgoing data-flow edges
     * that feed into other nodes (not used as argument or value).
     */
    private boolean isStandaloneCall(FlowNode callNode) {
        // Find the CALL_RESULT for this call
        var resultNode = findCallResult(callNode);
        if (resultNode == null) return true; // no result => standalone

        // Check if the result has outgoing edges that are data uses
        var outgoing = graph.outgoingEdgesOf(resultNode);
        for (var edge : outgoing) {
            var edgeKind = edge.kind();
            // CALL_RESULT_OF is structural, not a data use
            if (edgeKind != FlowEdgeKind.CALL_RESULT_OF) {
                return false; // result is consumed somewhere
            }
        }
        return true;
    }

    private FlowNode findCallResult(FlowNode callNode) {
        for (var edge : graph.outgoingEdgesOf(callNode)) {
            if (edge.kind() == FlowEdgeKind.CALL_RESULT_OF) {
                return graph.getEdgeTarget(edge);
            }
        }
        return null;
    }

    // ─── Pass 2: Render ────────────────────────────────────────────────

    private String renderChildBlock(String controlNodeId) {
        var roots = collectStatementRoots(controlNodeId);
        var sb = new StringBuilder();
        for (var root : roots) {
            var line = renderStatement(root);
            if (line != null && !line.isBlank()) {
                sb.append(indent(line)).append("\n");
            }
        }
        return sb.toString();
    }

    private String renderStatement(FlowNode node) {
        if (!visited.add(node.id())) return null;

        return switch (node.kind()) {
            case LOCAL_DEF -> renderLocalDef(node);
            case RETURN -> renderReturn(node);
            case CALL -> renderCallStatement(node);
            case OBJECT_CREATE -> renderObjectCreateStatement(node);
            case BRANCH -> renderBranch(node);
            case LOOP -> renderLoop(node);
            case FIELD_WRITE -> renderFieldWrite(node);
            case THROW -> renderThrow(node);
            case BREAK -> renderBreak(node);
            case CONTINUE -> renderContinue(node);
            case ASSERT -> renderAssert(node);
            case SYNCHRONIZED -> renderSynchronized(node);
            case YIELD -> renderYield(node);
            default -> "/* unknown: " + node.kind() + " */";
        };
    }

    /**
     * Render a node as an expression (recursive walk of the expression sub-tree).
     */
    private String renderExpr(FlowNode node) {
        if (node == null) return "/* ? */";

        return switch (node.kind()) {
            case BINARY_OP -> renderBinaryOp(node);
            case UNARY_OP -> renderUnaryOp(node);
            case LITERAL -> renderLiteral(node);
            case LOCAL_USE -> node.variableName() != null ? node.variableName() : "/* use */";
            case LOCAL_DEF -> node.variableName() != null ? node.variableName() : "/* def */";
            case CALL -> renderCallExpr(node);
            case CALL_RESULT -> renderCallResult(node);
            case CAST -> renderCast(node);
            case INSTANCEOF -> renderInstanceOf(node);
            case TERNARY -> renderTernary(node);
            case ARRAY_ACCESS -> renderArrayAccess(node);
            case OBJECT_CREATE -> renderObjectCreateExpr(node);
            case THIS_REF -> "this";
            case SUPER_REF -> "super";
            case PARAM -> node.variableName() != null ? node.variableName() : "/* param */";
            case MERGE_VALUE -> renderMergeValue(node);
            case FIELD_READ -> renderFieldRead(node);
            case FIELD_WRITE -> renderFieldWriteExpr(node);
            case ARRAY_CREATE -> renderArrayCreate(node);
            case LAMBDA -> renderLambdaExpr(node);
            case METHOD_REF -> renderMethodRefExpr(node);
            default -> "/* expr:" + node.kind() + " */";
        };
    }

    // ─── Statement renderers ───────────────────────────────────────────

    private String renderLocalDef(FlowNode node) {
        String name = node.variableName() != null ? node.variableName() : "v";
        boolean isDeclaration = node.variableVersion() <= 0;

        // Find the value source via DATA_DEP with label "init" or "value", or any incoming data edge
        var valueSource = findValueSource(node);

        if (isDeclaration) {
            String type = shortName(node.typeFqn() != null ? node.typeFqn() : "var");
            if (valueSource != null) {
                return type + " " + name + " = " + renderExpr(valueSource) + ";";
            }
            return type + " " + name + ";";
        } else {
            // Reassignment — no type prefix
            if (valueSource != null) {
                return name + " = " + renderExpr(valueSource) + ";";
            }
            return name + " = /* ? */;";
        }
    }

    private String renderReturn(FlowNode node) {
        var value = findEdgeSource(node, FlowEdgeKind.RETURN_DEP);
        if (value != null) {
            return "return " + renderExpr(value) + ";";
        }
        return "return;";
    }

    private String renderCallStatement(FlowNode node) {
        return renderCallExpr(node) + ";";
    }

    private String renderObjectCreateStatement(FlowNode node) {
        return renderObjectCreateExpr(node) + ";";
    }

    private String renderBranch(FlowNode node) {
        return switch (node.controlSubtype()) {
            case IF -> renderIfBranch(node);
            case TRY -> renderTryBranch(node);
            case SWITCH -> renderSwitchBranch(node);
            default -> "/* branch:" + node.controlSubtype() + " */";
        };
    }

    private String renderIfBranch(FlowNode node) {
        var sb = new StringBuilder();
        var cond = findEdgeSource(node, FlowEdgeKind.CONDITION);
        sb.append("if (").append(cond != null ? renderExpr(cond) : "/* ? */").append(") {\n");

        // Find child statements enclosed by this branch
        sb.append(renderChildBlock(node.id()));

        // Check for else branch: look for MERGE and then see if there's another set of children
        // For simplicity, we detect if there are LOCAL_DEF nodes with same variable name in both branches
        sb.append("}");

        // Heuristic: check if there are nodes with enclosingControlId == this branch's merge node
        // For now, keep it simple — just close the if
        var elseBlock = renderElseBlock(node);
        if (elseBlock != null && !elseBlock.isBlank()) {
            sb.append(" else {\n");
            sb.append(elseBlock);
            sb.append("}");
        }
        return sb.toString();
    }

    /**
     * Look for an else block: find the MERGE node for this IF, then check if there are
     * nodes referencing the merge that indicate an else path.
     * This is approximate — the flow graph doesn't explicitly separate then/else children.
     */
    private String renderElseBlock(FlowNode ifBranch) {
        // We look for a MERGE node connected to this branch via CONTROL_DEP
        for (var edge : graph.outgoingEdgesOf(ifBranch)) {
            // Actually the graph structure has branch -> children via enclosingControlId, not edges
            // We need a different approach: look at the original structure
        }
        // For now, return null — the children are all in renderChildBlock
        return null;
    }

    private String renderTryBranch(FlowNode node) {
        var sb = new StringBuilder();
        sb.append("try {\n");
        sb.append(renderChildBlock(node.id()));
        sb.append("}");

        // Find CATCH nodes linked via CONTROL_DEP
        for (var edge : graph.outgoingEdgesOf(node)) {
            if (edge.kind() == FlowEdgeKind.CONTROL_DEP) {
                var target = graph.getEdgeTarget(edge);
                if (target.kind() == FlowNodeKind.BRANCH && target.controlSubtype() == ControlSubtype.CATCH) {
                    String exType = target.label() != null && target.label().startsWith("catch ")
                            ? target.label().substring(6) : "Exception";
                    sb.append(" catch (").append(exType).append(" e) {\n");
                    sb.append(renderChildBlock(target.id()));
                    sb.append("}");
                }
            }
        }
        return sb.toString();
    }

    private String renderSwitchBranch(FlowNode node) {
        var sb = new StringBuilder();
        var selector = findEdgeSource(node, FlowEdgeKind.CONDITION);
        sb.append("switch (").append(selector != null ? renderExpr(selector) : "/* ? */").append(") {\n");
        sb.append(renderChildBlock(node.id()));
        sb.append("}");
        return sb.toString();
    }

    private String renderLoop(FlowNode node) {
        return switch (node.controlSubtype()) {
            case WHILE -> renderWhileLoop(node);
            case FOR -> renderForLoop(node);
            case FOREACH -> renderForEachLoop(node);
            case DO -> renderDoLoop(node);
            default -> "/* loop:" + node.controlSubtype() + " */";
        };
    }

    private String renderWhileLoop(FlowNode node) {
        var sb = new StringBuilder();
        var cond = findEdgeSource(node, FlowEdgeKind.CONDITION);
        sb.append("while (").append(cond != null ? renderExpr(cond) : "/* ? */").append(") {\n");
        sb.append(renderChildBlock(node.id()));
        sb.append("}");
        return sb.toString();
    }

    private String renderForLoop(FlowNode node) {
        var sb = new StringBuilder();
        var cond = findEdgeSource(node, FlowEdgeKind.CONDITION);

        // Find init and update from LOOP_INIT / LOOP_UPDATE edges
        var initNode = findEdgeSource(node, FlowEdgeKind.LOOP_INIT);
        var updateNode = findEdgeSource(node, FlowEdgeKind.LOOP_UPDATE);

        // Render init: if it's a LOCAL_DEF, render as declaration
        String initStr = "";
        if (initNode != null) {
            if (initNode.kind() == FlowNodeKind.LOCAL_DEF) {
                String type = shortName(initNode.typeFqn() != null ? initNode.typeFqn() : "int");
                String name = initNode.variableName() != null ? initNode.variableName() : "i";
                var initValue = findValueSource(initNode);
                initStr = type + " " + name + " = " + (initValue != null ? renderExpr(initValue) : "0");
            } else {
                initStr = renderExpr(initNode);
            }
        }

        String condStr = cond != null ? renderExpr(cond) : "";

        // Render update
        String updateStr = "";
        if (updateNode != null) {
            updateStr = renderExpr(updateNode);
        }

        sb.append("for (").append(initStr).append("; ").append(condStr).append("; ").append(updateStr).append(") {\n");
        sb.append(renderChildBlock(node.id()));
        sb.append("}");
        return sb.toString();
    }

    private String renderForEachLoop(FlowNode node) {
        var sb = new StringBuilder();
        var iterable = findEdgeSource(node, FlowEdgeKind.LOOP_ITERABLE);

        // Find the loop variable: LOCAL_DEF child of this loop
        String elemDecl = "var elem";
        var children = collectStatementRoots(node.id());
        for (var child : children) {
            if (child.kind() == FlowNodeKind.LOCAL_DEF && child.variableName() != null) {
                elemDecl = shortName(child.typeFqn() != null ? child.typeFqn() : "var") + " " + child.variableName();
                break;
            }
        }

        sb.append("for (").append(elemDecl).append(" : ");
        sb.append(iterable != null ? renderExpr(iterable) : "/* ? */");
        sb.append(") {\n");
        sb.append(renderChildBlock(node.id()));
        sb.append("}");
        return sb.toString();
    }

    private String renderDoLoop(FlowNode node) {
        var sb = new StringBuilder();
        var cond = findEdgeSource(node, FlowEdgeKind.CONDITION);
        sb.append("do {\n");
        sb.append(renderChildBlock(node.id()));
        sb.append("} while (").append(cond != null ? renderExpr(cond) : "/* ? */").append(");");
        return sb.toString();
    }

    private String renderFieldWrite(FlowNode node) {
        String fieldName = node.variableName() != null ? node.variableName() : "field";

        // Find receiver and value
        FlowNode receiver = null;
        FlowNode value = null;
        for (var edge : graph.incomingEdgesOf(node)) {
            var src = graph.getEdgeSource(edge);
            if (edge.kind() == FlowEdgeKind.DATA_DEP) {
                if ("this".equals(edge.label()) || "receiver".equals(edge.label())) {
                    receiver = src;
                } else if ("value".equals(edge.label())) {
                    value = src;
                } else if (value == null) {
                    value = src;
                }
            }
        }

        var sb = new StringBuilder();
        if (receiver != null && receiver.kind() != FlowNodeKind.THIS_REF) {
            sb.append(renderExpr(receiver)).append(".");
        }
        sb.append(fieldName).append(" = ");
        sb.append(value != null ? renderExpr(value) : "/* ? */");
        sb.append(";");
        return sb.toString();
    }

    private String renderThrow(FlowNode node) {
        var throwVal = findEdgeSource(node, FlowEdgeKind.THROW_VALUE);
        return "throw " + (throwVal != null ? renderExpr(throwVal) : "/* ? */") + ";";
    }

    private String renderBreak(FlowNode node) {
        String targetLabel = node.attr("targetLabel");
        return targetLabel != null ? "break " + targetLabel + ";" : "break;";
    }

    private String renderContinue(FlowNode node) {
        String targetLabel = node.attr("targetLabel");
        return targetLabel != null ? "continue " + targetLabel + ";" : "continue;";
    }

    private String renderAssert(FlowNode node) {
        var cond = findEdgeSource(node, FlowEdgeKind.CONDITION);
        var msg = findEdgeSource(node, FlowEdgeKind.ASSERT_MESSAGE);
        var sb = new StringBuilder("assert ");
        sb.append(cond != null ? renderExpr(cond) : "true");
        if (msg != null) {
            sb.append(" : ").append(renderExpr(msg));
        }
        sb.append(";");
        return sb.toString();
    }

    private String renderSynchronized(FlowNode node) {
        var lock = findEdgeSource(node, FlowEdgeKind.SYNC_LOCK);
        var sb = new StringBuilder();
        sb.append("synchronized (").append(lock != null ? renderExpr(lock) : "this").append(") {\n");
        sb.append(renderChildBlock(node.id()));
        sb.append("}");
        return sb.toString();
    }

    private String renderYield(FlowNode node) {
        var value = findEdgeSource(node, FlowEdgeKind.YIELD_VALUE);
        return "yield " + (value != null ? renderExpr(value) : "/* ? */") + ";";
    }

    // ─── Expression renderers ──────────────────────────────────────────

    private String renderBinaryOp(FlowNode node) {
        var left = findEdgeSource(node, FlowEdgeKind.LEFT_OPERAND);
        var right = findEdgeSource(node, FlowEdgeKind.RIGHT_OPERAND);

        String opName = node.attr("operator");
        String symbol = "?";
        if (opName != null) {
            try {
                symbol = BinaryOperator.valueOf(opName).symbol();
            } catch (IllegalArgumentException ignored) {}
        }

        String leftStr = left != null ? renderExpr(left) : "/* ? */";
        String rightStr = right != null ? renderExpr(right) : "/* ? */";
        return leftStr + " " + symbol + " " + rightStr;
    }

    private String renderUnaryOp(FlowNode node) {
        var operand = findEdgeSource(node, FlowEdgeKind.UNARY_OPERAND);
        String opName = node.attr("operator");
        String symbol = "?";
        boolean prefix = true;
        if (opName != null) {
            try {
                var op = UnaryOperator.valueOf(opName);
                symbol = op.symbol();
                prefix = op.prefix();
            } catch (IllegalArgumentException ignored) {}
        }

        String operandStr = operand != null ? renderExpr(operand) : "/* ? */";
        return prefix ? symbol + operandStr : operandStr + symbol;
    }

    private String renderLiteral(FlowNode node) {
        String literalType = node.attr("literalType");
        String value = node.attr("value");
        if (value == null) return node.label();
        if ("STRING".equals(literalType)) {
            return "\"" + escapeString(value) + "\"";
        }
        if ("CHAR".equals(literalType)) {
            return "'" + escapeString(value) + "'";
        }
        if ("NULL".equals(literalType)) {
            return "null";
        }
        if ("LONG".equals(literalType) && !value.endsWith("L") && !value.endsWith("l")) {
            return value + "L";
        }
        return value;
    }

    private String renderCallExpr(FlowNode node) {
        var sb = new StringBuilder();

        // Receiver
        var receiver = findEdgeSource(node, FlowEdgeKind.RECEIVER);
        if (receiver != null) {
            sb.append(renderExpr(receiver)).append(".");
        }

        // Method name
        String methodName = node.attr("methodName");
        if (methodName == null) {
            // Fallback: extract from label
            String label = node.label();
            if (label != null && label.endsWith("()")) {
                methodName = label.substring(0, label.length() - 2);
            } else {
                methodName = label != null ? label : "method";
            }
        }
        sb.append(methodName);

        // Args
        sb.append("(");
        var args = findArgSources(node);
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(renderExpr(args.get(i)));
        }
        sb.append(")");
        return sb.toString();
    }

    private String renderCallResult(FlowNode node) {
        // Transparent — render the CALL that produced this result
        var call = findCallForResult(node);
        if (call != null) {
            if (call.kind() == FlowNodeKind.OBJECT_CREATE) {
                return renderObjectCreateExpr(call);
            }
            return renderCallExpr(call);
        }
        return "/* callResult */";
    }

    private String renderCast(FlowNode node) {
        var operand = findEdgeSource(node, FlowEdgeKind.CAST_OPERAND);
        String targetType = node.attr("targetType");
        if (targetType == null) targetType = node.typeFqn() != null ? shortName(node.typeFqn()) : "Object";
        return "(" + shortName(targetType) + ") " + (operand != null ? renderExpr(operand) : "/* ? */");
    }

    private String renderInstanceOf(FlowNode node) {
        var operand = findEdgeSource(node, FlowEdgeKind.INSTANCEOF_OPERAND);
        String targetType = node.attr("targetType");
        if (targetType == null) targetType = "Object";
        String patternVar = node.attr("patternVar");
        var sb = new StringBuilder();
        sb.append(operand != null ? renderExpr(operand) : "/* ? */");
        sb.append(" instanceof ").append(shortName(targetType));
        if (patternVar != null) {
            sb.append(" ").append(patternVar);
        }
        return sb.toString();
    }

    private String renderTernary(FlowNode node) {
        var cond = findEdgeSource(node, FlowEdgeKind.TERNARY_CONDITION);
        var thenVal = findEdgeSource(node, FlowEdgeKind.TERNARY_THEN);
        var elseVal = findEdgeSource(node, FlowEdgeKind.TERNARY_ELSE);
        return (cond != null ? renderExpr(cond) : "/* ? */")
                + " ? " + (thenVal != null ? renderExpr(thenVal) : "/* ? */")
                + " : " + (elseVal != null ? renderExpr(elseVal) : "/* ? */");
    }

    private String renderArrayAccess(FlowNode node) {
        var arr = findEdgeSource(node, FlowEdgeKind.ARRAY_REF);
        var idx = findEdgeSource(node, FlowEdgeKind.ARRAY_INDEX);
        return (arr != null ? renderExpr(arr) : "/* ? */")
                + "[" + (idx != null ? renderExpr(idx) : "/* ? */") + "]";
    }

    private String renderObjectCreateExpr(FlowNode node) {
        var sb = new StringBuilder("new ");
        String typeName = node.typeFqn() != null ? shortName(node.typeFqn()) : "Object";
        // Also try label for more specific info
        String label = node.label();
        if (label != null && label.startsWith("new ")) {
            typeName = label.substring(4);
        }
        sb.append(typeName).append("(");
        var args = findArgSources(node);
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(renderExpr(args.get(i)));
        }
        sb.append(")");
        return sb.toString();
    }

    private String renderMergeValue(FlowNode node) {
        // Transparent — render the first PHI_INPUT source
        for (var edge : graph.incomingEdgesOf(node)) {
            if (edge.kind() == FlowEdgeKind.PHI_INPUT) {
                var src = graph.getEdgeSource(edge);
                // Just use the variable name if available
                if (node.variableName() != null) {
                    return node.variableName();
                }
                return renderExpr(src);
            }
        }
        return node.variableName() != null ? node.variableName() : "/* merge */";
    }

    private String renderFieldRead(FlowNode node) {
        String fieldName = node.variableName() != null ? node.variableName() : node.label();
        if (fieldName == null) fieldName = "field";

        // Clean up "this." prefix from label if we're going to add receiver explicitly
        if (fieldName.startsWith("this.")) {
            fieldName = fieldName.substring(5);
        }

        // Find receiver via DATA_DEP with label "receiver" or "this"
        FlowNode receiver = null;
        for (var edge : graph.incomingEdgesOf(node)) {
            var src = graph.getEdgeSource(edge);
            if (edge.kind() == FlowEdgeKind.DATA_DEP
                    && ("this".equals(edge.label()) || "receiver".equals(edge.label()))) {
                receiver = src;
                break;
            }
        }

        if (receiver != null && receiver.kind() != FlowNodeKind.THIS_REF) {
            return renderExpr(receiver) + "." + fieldName;
        }
        return fieldName;
    }

    private String renderFieldWriteExpr(FlowNode node) {
        // When used as expression, render as assignment expression
        return renderFieldWrite(node).replaceAll(";$", "");
    }

    private String renderArrayCreate(FlowNode node) {
        String elementType = node.attr("elementType");
        if (elementType == null) elementType = "Object";
        var dims = new ArrayList<FlowNode>();
        for (var edge : graph.incomingEdgesOf(node)) {
            if (edge.kind() == FlowEdgeKind.ARRAY_DIM) {
                dims.add(graph.getEdgeSource(edge));
            }
        }
        var sb = new StringBuilder("new ").append(elementType);
        if (dims.isEmpty()) {
            sb.append("[]");
        } else {
            for (var dim : dims) {
                sb.append("[").append(renderExpr(dim)).append("]");
            }
        }
        return sb.toString();
    }

    private String renderLambdaExpr(FlowNode node) {
        String paramNames = node.attr("paramNames");
        if (paramNames == null || paramNames.isEmpty()) {
            return "() -> { /* ... */ }";
        }
        return "(" + paramNames + ") -> { /* ... */ }";
    }

    private String renderMethodRefExpr(FlowNode node) {
        String targetType = node.attr("targetType");
        String methodName = node.attr("methodName");
        if (targetType == null) targetType = "?";
        if (methodName == null) methodName = "?";
        return targetType + "::" + methodName;
    }

    // ─── Graph navigation helpers ──────────────────────────────────────

    /**
     * Find the source node of an incoming edge of the given kind.
     */
    private FlowNode findEdgeSource(FlowNode target, FlowEdgeKind edgeKind) {
        for (var edge : graph.incomingEdgesOf(target)) {
            if (edge.kind() == edgeKind) {
                return graph.getEdgeSource(edge);
            }
        }
        return null;
    }

    /**
     * Find the value source for a LOCAL_DEF — via DATA_DEP with label "init" or "value".
     */
    private FlowNode findValueSource(FlowNode defNode) {
        // First try specific labels
        for (var edge : graph.incomingEdgesOf(defNode)) {
            if (edge.kind() == FlowEdgeKind.DATA_DEP) {
                String label = edge.label();
                if ("init".equals(label) || "value".equals(label)) {
                    return graph.getEdgeSource(edge);
                }
            }
        }
        // Fallback: any DATA_DEP
        for (var edge : graph.incomingEdgesOf(defNode)) {
            if (edge.kind() == FlowEdgeKind.DATA_DEP) {
                return graph.getEdgeSource(edge);
            }
        }
        return null;
    }

    /**
     * Find ARG_PASS sources for a CALL/OBJECT_CREATE node, sorted by label (arg index).
     */
    private List<FlowNode> findArgSources(FlowNode callNode) {
        return graph.incomingEdgesOf(callNode).stream()
                .filter(e -> e.kind() == FlowEdgeKind.ARG_PASS)
                .sorted(Comparator.comparing(FlowEdge::label))
                .map(graph::getEdgeSource)
                .collect(Collectors.toList());
    }

    /**
     * Find the CALL/OBJECT_CREATE node for a CALL_RESULT via CALL_RESULT_OF edge.
     */
    private FlowNode findCallForResult(FlowNode resultNode) {
        for (var edge : graph.incomingEdgesOf(resultNode)) {
            if (edge.kind() == FlowEdgeKind.CALL_RESULT_OF) {
                return graph.getEdgeSource(edge);
            }
        }
        return null;
    }

    // ─── Utility ───────────────────────────────────────────────────────

    private static String shortName(String fqn) {
        if (fqn == null) return "var";
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static String indent(String block) {
        var sb = new StringBuilder();
        for (var line : block.split("\n", -1)) {
            if (!line.isEmpty()) {
                sb.append("    ").append(line);
            }
            sb.append("\n");
        }
        // Remove trailing newline since caller adds one
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
