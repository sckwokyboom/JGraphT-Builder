package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.sckwoky.typegraph.flow.model.*;
import com.github.sckwoky.typegraph.model.MethodSignature;

import java.util.*;

/**
 * Builds a {@link MethodFlowGraph} from a {@link MethodDeclaration} or
 * {@link ConstructorDeclaration} body.
 * <p>
 * Maintains an SSA-lite scope ({@link VariableState}) and an enclosing-control
 * stack so that every non-control node is tagged with its dominating BRANCH or
 * LOOP id ({@link FlowNode#enclosingControlId()}). The {@link BackwardSlicer}
 * uses these tags to include control nodes in a slice only when they actually
 * dominate a data-relevant node.
 *
 * <h2>Documented limitations</h2>
 * <ul>
 *   <li>Loops are processed once; loop-carried dependencies are approximated
 *       by a single phi merge of pre- and post-iteration versions.</li>
 *   <li>Try/catch is conservatively over-approximated: every catch branch is
 *       reachable from the try entry.</li>
 *   <li>No alias analysis: {@code var d = this.field; modify(d);} does not link
 *       {@code d} back to {@code this.field}.</li>
 *   <li>Lambda bodies are opaque; the lambda itself becomes a LAMBDA node.</li>
 *   <li>Arrays are monolithic; element-wise tracking is not modeled.</li>
 * </ul>
 */
public class MethodFlowBuilder {

    private final FieldIndex fields;
    private final String declaringTypeFqn;
    private MethodFlowGraph graph;
    private VariableState scope;
    private final Deque<String> enclosingControlStack = new ArrayDeque<>();
    private FlowNode thisRef;
    private FlowNode superRef;
    private int stmtCounter = 0;

    public MethodFlowBuilder(String declaringTypeFqn, FieldIndex fields) {
        this.declaringTypeFqn = declaringTypeFqn;
        this.fields = fields;
    }

    // ─── Entry points ───────────────────────────────────────────────────

    public MethodFlowGraph build(MethodDeclaration md) {
        var sig = signatureOf(md);
        graph = new MethodFlowGraph(sig);
        scope = new VariableState();
        stmtCounter = 0;
        if (!md.isStatic()) {
            thisRef = mkNode(FlowNodeKind.THIS_REF, "this", lineOf(md), declaringTypeFqn,
                    null, -1, null, null, null, ControlSubtype.NONE);
        }
        for (var p : md.getParameters()) {
            var pn = mkNode(FlowNodeKind.PARAM, "param " + p.getNameAsString(),
                    lineOf(p), p.getType().asString(),
                    p.getNameAsString(), 0, null, null, null, ControlSubtype.NONE);
            scope.define(p.getNameAsString(), pn);
        }
        md.getBody().ifPresent(this::processBlock);
        return graph;
    }

    public MethodFlowGraph build(ConstructorDeclaration cd) {
        var sig = signatureOf(cd);
        graph = new MethodFlowGraph(sig);
        scope = new VariableState();
        stmtCounter = 0;
        thisRef = mkNode(FlowNodeKind.THIS_REF, "this", lineOf(cd), declaringTypeFqn,
                null, -1, null, null, null, ControlSubtype.NONE);
        for (var p : cd.getParameters()) {
            var pn = mkNode(FlowNodeKind.PARAM, "param " + p.getNameAsString(),
                    lineOf(p), p.getType().asString(),
                    p.getNameAsString(), 0, null, null, null, ControlSubtype.NONE);
            scope.define(p.getNameAsString(), pn);
        }
        processBlock(cd.getBody());
        return graph;
    }

    // ─── Statement dispatch ─────────────────────────────────────────────

    private void processBlock(BlockStmt block) {
        scope.pushFrame();
        try {
            for (var s : block.getStatements()) {
                processStmt(s);
            }
        } finally {
            scope.popFrame();
        }
    }

    private void processStmt(Statement s) {
        if (s instanceof ExpressionStmt es) {
            analyzeExpr(es.getExpression());
        } else if (s instanceof ReturnStmt rs) {
            processReturn(rs);
        } else if (s instanceof IfStmt is) {
            processIf(is);
        } else if (s instanceof BlockStmt bs) {
            processBlock(bs);
        } else if (s instanceof WhileStmt ws) {
            processWhile(ws);
        } else if (s instanceof DoStmt ds) {
            processDo(ds);
        } else if (s instanceof ForStmt fs) {
            processFor(fs);
        } else if (s instanceof ForEachStmt fes) {
            processForEach(fes);
        } else if (s instanceof TryStmt ts) {
            processTry(ts);
        } else if (s instanceof SwitchStmt ss) {
            processSwitch(ss);
        } else if (s instanceof ThrowStmt ths) {
            analyzeExpr(ths.getExpression());
        } else if (s instanceof SynchronizedStmt ss) {
            analyzeExpr(ss.getExpression());
            processBlock(ss.getBody());
        } else if (s instanceof LabeledStmt ls) {
            processStmt(ls.getStatement());
        } else if (s instanceof AssertStmt as) {
            analyzeExpr(as.getCheck());
            as.getMessage().ifPresent(this::analyzeExpr);
        }
        // BreakStmt, ContinueStmt, EmptyStmt, LocalClassDeclarationStmt, etc. → ignored
    }

    // ─── Control flow ───────────────────────────────────────────────────

    private void processReturn(ReturnStmt rs) {
        var ret = mkNode(FlowNodeKind.RETURN, "return", lineOf(rs), null,
                null, -1, null, null, null, ControlSubtype.NONE);
        rs.getExpression().ifPresent(expr -> {
            var val = analyzeExpr(expr);
            if (val != null) graph.addEdge(val, ret, FlowEdgeKind.RETURN_DEP);
        });
    }

    private void processIf(IfStmt is) {
        var condVal = analyzeExpr(is.getCondition());
        var branch = mkControl(FlowNodeKind.BRANCH, ControlSubtype.IF, "if");
        if (condVal != null) graph.addEdge(condVal, branch, FlowEdgeKind.DATA_DEP, "cond");

        var before = scope.snapshot();
        enclosingControlStack.push(branch.id());

        // then
        scope.pushFrame();
        processStmt(is.getThenStmt());
        var thenSnap = scope.snapshot();
        scope.popFrame();
        scope.restoreFromSnapshot(before);

        // else
        Map<String, FlowNode> elseSnap;
        if (is.getElseStmt().isPresent()) {
            scope.pushFrame();
            processStmt(is.getElseStmt().get());
            elseSnap = scope.snapshot();
            scope.popFrame();
            scope.restoreFromSnapshot(before);
        } else {
            elseSnap = before;
        }

        enclosingControlStack.pop();

        var merge = mkControl(FlowNodeKind.MERGE, ControlSubtype.IF, "merge");
        graph.addEdge(branch, merge, FlowEdgeKind.CONTROL_DEP);
        mergeBranches(before, List.of(thenSnap, elseSnap), merge);
    }

    private void processWhile(WhileStmt ws) {
        var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.WHILE, "while");
        var cond = analyzeExpr(ws.getCondition());
        if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.DATA_DEP, "cond");

        var before = scope.snapshot();
        enclosingControlStack.push(loop.id());
        scope.pushFrame();
        processStmt(ws.getBody());
        var after = scope.snapshot();
        scope.popFrame();
        enclosingControlStack.pop();
        scope.restoreFromSnapshot(before);

        mergeBranches(before, List.of(before, after), loop);
    }

    private void processDo(DoStmt ds) {
        var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.DO, "do-while");
        var before = scope.snapshot();
        enclosingControlStack.push(loop.id());
        scope.pushFrame();
        processStmt(ds.getBody());
        var after = scope.snapshot();
        scope.popFrame();
        enclosingControlStack.pop();

        var cond = analyzeExpr(ds.getCondition());
        if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.DATA_DEP, "cond");

        scope.restoreFromSnapshot(before);
        mergeBranches(before, List.of(before, after), loop);
    }

    private void processFor(ForStmt fs) {
        scope.pushFrame();
        for (var init : fs.getInitialization()) analyzeExpr(init);
        var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.FOR, "for");
        fs.getCompare().ifPresent(c -> {
            var cond = analyzeExpr(c);
            if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.DATA_DEP, "cond");
        });

        var before = scope.snapshot();
        enclosingControlStack.push(loop.id());
        scope.pushFrame();
        processStmt(fs.getBody());
        for (var upd : fs.getUpdate()) analyzeExpr(upd);
        var after = scope.snapshot();
        scope.popFrame();
        enclosingControlStack.pop();
        scope.restoreFromSnapshot(before);
        mergeBranches(before, List.of(before, after), loop);

        scope.popFrame();
    }

    private void processForEach(ForEachStmt fes) {
        var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.FOREACH, "foreach");
        var iter = analyzeExpr(fes.getIterable());
        if (iter != null) graph.addEdge(iter, loop, FlowEdgeKind.DATA_DEP, "iterable");

        var before = scope.snapshot();
        enclosingControlStack.push(loop.id());
        scope.pushFrame();

        for (var v : fes.getVariable().getVariables()) {
            var loopVar = mkNode(FlowNodeKind.LOCAL_DEF, v.getNameAsString(),
                    lineOf(fes), v.getType().asString(),
                    v.getNameAsString(), scope.nextVersion(v.getNameAsString()),
                    null, null, null, ControlSubtype.NONE);
            if (iter != null) graph.addEdge(iter, loopVar, FlowEdgeKind.DATA_DEP, "iter-elem");
            scope.define(v.getNameAsString(), loopVar);
        }

        processStmt(fes.getBody());
        var after = scope.snapshot();

        scope.popFrame();
        enclosingControlStack.pop();
        scope.restoreFromSnapshot(before);
        mergeBranches(before, List.of(before, after), loop);
    }

    private void processTry(TryStmt ts) {
        var tryNode = mkControl(FlowNodeKind.BRANCH, ControlSubtype.TRY, "try");
        enclosingControlStack.push(tryNode.id());
        var before = scope.snapshot();

        scope.pushFrame();
        processBlock(ts.getTryBlock());
        var trySnap = scope.snapshot();
        scope.popFrame();
        scope.restoreFromSnapshot(before);

        var allSnaps = new ArrayList<Map<String, FlowNode>>();
        allSnaps.add(trySnap);

        for (var cc : ts.getCatchClauses()) {
            var catchNode = mkControl(FlowNodeKind.BRANCH, ControlSubtype.CATCH,
                    "catch " + cc.getParameter().getType().asString());
            graph.addEdge(tryNode, catchNode, FlowEdgeKind.CONTROL_DEP);
            enclosingControlStack.push(catchNode.id());
            scope.pushFrame();

            var p = cc.getParameter();
            var catchParam = mkNode(FlowNodeKind.PARAM, "catch " + p.getNameAsString(),
                    lineOf(p), p.getType().asString(),
                    p.getNameAsString(), 0, null, null, null, ControlSubtype.NONE);
            scope.define(p.getNameAsString(), catchParam);

            processBlock(cc.getBody());
            allSnaps.add(scope.snapshot());

            scope.popFrame();
            enclosingControlStack.pop();
            scope.restoreFromSnapshot(before);
        }

        enclosingControlStack.pop();

        var merge = mkControl(FlowNodeKind.MERGE, ControlSubtype.TRY, "try-merge");
        graph.addEdge(tryNode, merge, FlowEdgeKind.CONTROL_DEP);
        mergeBranches(before, allSnaps, merge);

        if (ts.getFinallyBlock().isPresent()) {
            var fin = mkControl(FlowNodeKind.BRANCH, ControlSubtype.FINALLY, "finally");
            graph.addEdge(merge, fin, FlowEdgeKind.CONTROL_DEP);
            enclosingControlStack.push(fin.id());
            scope.pushFrame();
            processBlock(ts.getFinallyBlock().get());
            scope.popFrame();
            enclosingControlStack.pop();
        }
    }

    private void processSwitch(SwitchStmt ss) {
        var sel = analyzeExpr(ss.getSelector());
        var branch = mkControl(FlowNodeKind.BRANCH, ControlSubtype.SWITCH, "switch");
        if (sel != null) graph.addEdge(sel, branch, FlowEdgeKind.DATA_DEP, "selector");

        var before = scope.snapshot();
        enclosingControlStack.push(branch.id());

        var caseSnaps = new ArrayList<Map<String, FlowNode>>();
        for (var entry : ss.getEntries()) {
            scope.pushFrame();
            for (var stmt : entry.getStatements()) processStmt(stmt);
            caseSnaps.add(scope.snapshot());
            scope.popFrame();
            scope.restoreFromSnapshot(before);
        }

        enclosingControlStack.pop();

        var merge = mkControl(FlowNodeKind.MERGE, ControlSubtype.SWITCH, "switch-merge");
        graph.addEdge(branch, merge, FlowEdgeKind.CONTROL_DEP);
        if (caseSnaps.isEmpty()) caseSnaps.add(before);
        mergeBranches(before, caseSnaps, merge);
    }

    // ─── Phi merging ────────────────────────────────────────────────────

    private void mergeBranches(Map<String, FlowNode> before,
                               List<Map<String, FlowNode>> branchSnaps,
                               FlowNode controlMerge) {
        var changed = new LinkedHashSet<String>();
        for (var snap : branchSnaps) {
            for (var entry : snap.entrySet()) {
                var beforeDef = before.get(entry.getKey());
                if (beforeDef != entry.getValue()) changed.add(entry.getKey());
            }
        }
        for (var name : changed) {
            String typeFqn = null;
            for (var snap : branchSnaps) {
                var v = snap.getOrDefault(name, before.get(name));
                if (v != null && v.typeFqn() != null) { typeFqn = v.typeFqn(); break; }
            }
            var phi = mkNode(FlowNodeKind.MERGE_VALUE, "phi(" + name + ")", -1, typeFqn,
                    name, scope.nextVersion(name), null, null, null, ControlSubtype.NONE);
            graph.addEdge(controlMerge, phi, FlowEdgeKind.CONTROL_DEP);
            for (var snap : branchSnaps) {
                var src = snap.getOrDefault(name, before.get(name));
                if (src != null) graph.addEdge(src, phi, FlowEdgeKind.PHI_INPUT);
            }
            scope.update(name, phi);
        }
    }

    // ─── Expression analysis ────────────────────────────────────────────

    private FlowNode analyzeExpr(Expression expr) {
        if (expr == null) return null;

        if (expr instanceof NameExpr ne) return analyzeName(ne);
        if (expr instanceof FieldAccessExpr fae) return analyzeFieldAccess(fae);
        if (expr instanceof ThisExpr) return ensureThisRef();
        if (expr instanceof SuperExpr) return ensureSuperRef();
        if (expr instanceof MethodCallExpr mce) return analyzeMethodCall(mce);
        if (expr instanceof ObjectCreationExpr oce) return analyzeObjectCreation(oce);
        if (expr instanceof AssignExpr ae) return analyzeAssign(ae);
        if (expr instanceof VariableDeclarationExpr vde) return analyzeVarDecl(vde);
        if (expr instanceof BinaryExpr be) return analyzeBinary(be);
        if (expr instanceof UnaryExpr ue) return analyzeUnary(ue);
        if (expr instanceof CastExpr ce) return analyzeCast(ce);
        if (expr instanceof EnclosedExpr ee) return analyzeExpr(ee.getInner());
        if (expr instanceof ConditionalExpr ce) return analyzeTernary(ce);
        if (expr instanceof LiteralExpr le) return analyzeLiteral(le);
        if (expr instanceof ArrayAccessExpr aae) return analyzeArrayAccess(aae);
        if (expr instanceof InstanceOfExpr ioe) return analyzeInstanceOf(ioe);
        if (expr instanceof LambdaExpr le) return analyzeLambda(le);
        if (expr instanceof MethodReferenceExpr mre) return analyzeMethodReference(mre);
        if (expr instanceof ArrayCreationExpr ace) return analyzeArrayCreation(ace);
        // Fallback
        return mkTemp("expr:" + expr.getClass().getSimpleName(), null);
    }

    private FlowNode analyzeName(NameExpr ne) {
        String name = ne.getNameAsString();
        var current = scope.currentDef(name);
        if (current != null) return current;
        if (fields.contains(name)) {
            var fr = mkNode(FlowNodeKind.FIELD_READ, "this." + name, lineOf(ne),
                    fields.typeOf(name), name, -1, null, null,
                    FieldOrigin.THIS, ControlSubtype.NONE);
            // Wire receiver
            if (thisRef != null) graph.addEdge(thisRef, fr, FlowEdgeKind.DATA_DEP, "this");
            return fr;
        }
        // Unknown reference: model as a TEMP_EXPR placeholder
        return mkTemp("ref:" + name, null);
    }

    private FlowNode analyzeFieldAccess(FieldAccessExpr fae) {
        FieldOrigin origin = FieldOrigin.OTHER;
        FlowNode receiver = null;
        if (fae.getScope() instanceof ThisExpr) {
            origin = FieldOrigin.THIS;
            receiver = ensureThisRef();
        } else if (fae.getScope() instanceof NameExpr neScope) {
            // could be ClassName.STATIC or var.field — best effort
            if (Character.isUpperCase(neScope.getNameAsString().charAt(0))
                    && scope.currentDef(neScope.getNameAsString()) == null) {
                origin = FieldOrigin.STATIC;
            } else {
                receiver = analyzeExpr(neScope);
            }
        } else {
            receiver = analyzeExpr(fae.getScope());
        }
        var fr = mkNode(FlowNodeKind.FIELD_READ, fae.getNameAsString(),
                lineOf(fae), null, fae.getNameAsString(), -1, null, null, origin, ControlSubtype.NONE);
        if (receiver != null) graph.addEdge(receiver, fr, FlowEdgeKind.DATA_DEP, "receiver");
        return fr;
    }

    private FlowNode analyzeMethodCall(MethodCallExpr mce) {
        // Receiver
        FlowNode receiver = mce.getScope().map(this::analyzeExpr).orElse(null);

        // Args
        var args = new ArrayList<FlowNode>();
        for (var a : mce.getArguments()) args.add(analyzeExpr(a));

        // Resolution
        MethodSignature sig = null;
        CallResolution res = CallResolution.UNRESOLVED;
        String returnType = null;
        String declaring = null;
        try {
            var resolved = mce.resolve();
            var paramTypes = new ArrayList<String>();
            for (int i = 0; i < resolved.getNumberOfParams(); i++) {
                paramTypes.add(resolved.getParam(i).getType().describe());
            }
            declaring = resolved.declaringType().getQualifiedName();
            returnType = resolved.getReturnType().describe();
            sig = new MethodSignature(declaring, resolved.getName(), paramTypes, returnType);
            res = CallResolution.RESOLVED;
        } catch (Throwable t) {
            res = CallResolution.UNRESOLVED;
        }

        // Determine call style
        String callStyle;
        if (mce.getScope().isEmpty()) {
            callStyle = CallStyle.METHOD.name();
        } else if (receiver != null && receiver.kind() == FlowNodeKind.THIS_REF) {
            callStyle = CallStyle.METHOD.name();
        } else if (declaring != null && mce.getScope().isPresent()
                && mce.getScope().get() instanceof NameExpr nameScope
                && Character.isUpperCase(nameScope.getNameAsString().charAt(0))
                && scope.currentDef(nameScope.getNameAsString()) == null) {
            callStyle = CallStyle.STATIC.name();
        } else if (mce.getScope().isPresent()) {
            callStyle = CallStyle.CHAINED.name();
        } else {
            callStyle = CallStyle.METHOD.name();
        }

        var callAttrs = new HashMap<String, String>();
        callAttrs.put("methodName", mce.getNameAsString());
        callAttrs.put("callStyle", callStyle);

        var call = new FlowNode(graph.nextId(prefixOf(FlowNodeKind.CALL)),
                FlowNodeKind.CALL, mce.getNameAsString() + "()", lineOf(mce),
                returnType, null, -1, sig, res, null, ControlSubtype.NONE,
                currentEnclosing(), callAttrs, stmtCounter++);
        graph.addNode(call);

        if (receiver != null) graph.addEdge(receiver, call, FlowEdgeKind.RECEIVER);
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i) != null) {
                graph.addEdge(args.get(i), call, FlowEdgeKind.ARG_PASS, String.valueOf(i));
            }
        }

        var result = mkNode(FlowNodeKind.CALL_RESULT, mce.getNameAsString() + "→",
                lineOf(mce), returnType, null, -1, sig, res, null, ControlSubtype.NONE);
        graph.addEdge(call, result, FlowEdgeKind.CALL_RESULT_OF);
        return result;
    }

    private FlowNode analyzeObjectCreation(ObjectCreationExpr oce) {
        var args = new ArrayList<FlowNode>();
        for (var a : oce.getArguments()) args.add(analyzeExpr(a));

        MethodSignature sig = null;
        CallResolution res = CallResolution.UNRESOLVED;
        String returnType = oce.getType().asString();
        try {
            var resolved = oce.resolve();
            var paramTypes = new ArrayList<String>();
            for (int i = 0; i < resolved.getNumberOfParams(); i++) {
                paramTypes.add(resolved.getParam(i).getType().describe());
            }
            String declaring = resolved.declaringType().getQualifiedName();
            returnType = declaring;
            sig = new MethodSignature(declaring, "<init>", paramTypes, declaring);
            res = CallResolution.RESOLVED;
        } catch (Throwable t) {
            res = CallResolution.UNRESOLVED;
        }

        var objAttrs = new HashMap<String, String>();
        objAttrs.put("methodName", "<init>");
        objAttrs.put("callStyle", CallStyle.CONSTRUCTOR.name());

        var call = new FlowNode(graph.nextId(prefixOf(FlowNodeKind.OBJECT_CREATE)),
                FlowNodeKind.OBJECT_CREATE, "new " + oce.getType().asString(), lineOf(oce),
                returnType, null, -1, sig, res, null, ControlSubtype.NONE,
                currentEnclosing(), objAttrs, stmtCounter++);
        graph.addNode(call);

        for (int i = 0; i < args.size(); i++) {
            if (args.get(i) != null) {
                graph.addEdge(args.get(i), call, FlowEdgeKind.ARG_PASS, String.valueOf(i));
            }
        }
        var result = mkNode(FlowNodeKind.CALL_RESULT, oce.getType().asString(),
                lineOf(oce), returnType, null, -1, sig, res, null, ControlSubtype.NONE);
        graph.addEdge(call, result, FlowEdgeKind.CALL_RESULT_OF);
        return result;
    }

    private FlowNode analyzeAssign(AssignExpr ae) {
        var rhs = analyzeExpr(ae.getValue());
        var target = ae.getTarget();
        if (target instanceof NameExpr ne) {
            String name = ne.getNameAsString();
            if (scope.currentDef(name) != null || !fields.contains(name)) {
                var def = mkNode(FlowNodeKind.LOCAL_DEF, name + ":=", lineOf(ae),
                        rhs != null ? rhs.typeFqn() : null,
                        name, scope.nextVersion(name), null, null, null, ControlSubtype.NONE);
                if (rhs != null) graph.addEdge(rhs, def, FlowEdgeKind.DATA_DEP, "value");
                scope.update(name, def);
                return def;
            } else {
                var fw = mkNode(FlowNodeKind.FIELD_WRITE, "this." + name + ":=",
                        lineOf(ae), fields.typeOf(name), name, -1, null, null,
                        FieldOrigin.THIS, ControlSubtype.NONE);
                if (thisRef != null) graph.addEdge(thisRef, fw, FlowEdgeKind.DATA_DEP, "this");
                if (rhs != null) graph.addEdge(rhs, fw, FlowEdgeKind.DATA_DEP, "value");
                return fw;
            }
        }
        if (target instanceof FieldAccessExpr fae) {
            FieldOrigin origin = fae.getScope() instanceof ThisExpr ? FieldOrigin.THIS : FieldOrigin.OTHER;
            FlowNode receiver = fae.getScope() instanceof ThisExpr ? ensureThisRef() : analyzeExpr(fae.getScope());
            var fw = mkNode(FlowNodeKind.FIELD_WRITE, fae.getNameAsString() + ":=",
                    lineOf(ae), null, fae.getNameAsString(), -1, null, null, origin, ControlSubtype.NONE);
            if (receiver != null) graph.addEdge(receiver, fw, FlowEdgeKind.DATA_DEP, "receiver");
            if (rhs != null) graph.addEdge(rhs, fw, FlowEdgeKind.DATA_DEP, "value");
            return fw;
        }
        if (target instanceof ArrayAccessExpr aae) {
            var arr = analyzeExpr(aae.getName());
            if (rhs != null && arr != null) graph.addEdge(rhs, arr, FlowEdgeKind.DATA_DEP, "elem");
            return rhs;
        }
        return rhs;
    }

    private FlowNode analyzeVarDecl(VariableDeclarationExpr vde) {
        FlowNode last = null;
        for (var v : vde.getVariables()) {
            var name = v.getNameAsString();
            FlowNode init = v.getInitializer().map(this::analyzeExpr).orElse(null);
            var def = mkNode(FlowNodeKind.LOCAL_DEF, name + " :=", lineOf(v),
                    v.getType().asString(),
                    name, scope.nextVersion(name), null, null, null, ControlSubtype.NONE);
            if (init != null) graph.addEdge(init, def, FlowEdgeKind.DATA_DEP, "init");
            scope.define(name, def);
            last = def;
        }
        return last;
    }

    // ─── Task 5: Binary, Unary, Cast, InstanceOf, Literal ───────────────

    private FlowNode analyzeBinary(BinaryExpr be) {
        var l = analyzeExpr(be.getLeft());
        var r = analyzeExpr(be.getRight());
        var op = BinaryOperator.fromJavaParser(be.getOperator());
        String resultType = switch (op) {
            case AND, OR, EQ, NE, LT, GT, LE, GE -> "boolean";
            default -> null;
        };
        var attrs = new HashMap<String, String>();
        attrs.put("operator", op.name());
        var node = mkExpr(FlowNodeKind.BINARY_OP, lineOf(be), resultType, attrs);
        if (l != null) graph.addEdge(l, node, FlowEdgeKind.LEFT_OPERAND);
        if (r != null) graph.addEdge(r, node, FlowEdgeKind.RIGHT_OPERAND);
        return node;
    }

    private FlowNode analyzeUnary(UnaryExpr ue) {
        var inner = analyzeExpr(ue.getExpression());
        var op = UnaryOperator.fromJavaParser(ue.getOperator());
        var attrs = new HashMap<String, String>();
        attrs.put("operator", op.name());
        var node = mkExpr(FlowNodeKind.UNARY_OP, lineOf(ue),
                inner != null ? inner.typeFqn() : null, attrs);
        if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.UNARY_OPERAND);
        return node;
    }

    private FlowNode analyzeCast(CastExpr ce) {
        var inner = analyzeExpr(ce.getExpression());
        var targetType = ce.getType().asString();
        var attrs = new HashMap<String, String>();
        attrs.put("targetType", targetType);
        var node = mkExpr(FlowNodeKind.CAST, lineOf(ce), targetType, attrs);
        if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.CAST_OPERAND);
        return node;
    }

    private FlowNode analyzeInstanceOf(InstanceOfExpr ioe) {
        var inner = analyzeExpr(ioe.getExpression());
        var attrs = new HashMap<String, String>();
        attrs.put("targetType", ioe.getType().asString());
        ioe.getPattern().ifPresent(pat -> {
            if (pat instanceof TypePatternExpr tpe) {
                attrs.put("patternVar", tpe.getNameAsString());
            }
        });
        var node = mkExpr(FlowNodeKind.INSTANCEOF, lineOf(ioe), "boolean", attrs);
        if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.INSTANCEOF_OPERAND);
        return node;
    }

    private FlowNode analyzeLiteral(LiteralExpr le) {
        String typeFqn;
        String canonicalValue;
        String literalType;

        if (le instanceof IntegerLiteralExpr ile) {
            typeFqn = "int";
            canonicalValue = ile.getValue();
            literalType = LiteralType.INT.name();
        } else if (le instanceof LongLiteralExpr lle) {
            typeFqn = "long";
            canonicalValue = lle.getValue();
            literalType = LiteralType.LONG.name();
        } else if (le instanceof DoubleLiteralExpr dle) {
            typeFqn = "double";
            canonicalValue = dle.getValue();
            literalType = LiteralType.DOUBLE.name();
        } else if (le instanceof StringLiteralExpr sle) {
            typeFqn = "java.lang.String";
            canonicalValue = sle.asString();
            literalType = LiteralType.STRING.name();
        } else if (le instanceof CharLiteralExpr cle) {
            typeFqn = "char";
            canonicalValue = cle.getValue();
            literalType = LiteralType.CHAR.name();
        } else if (le instanceof BooleanLiteralExpr ble) {
            typeFqn = "boolean";
            canonicalValue = String.valueOf(ble.getValue());
            literalType = LiteralType.BOOLEAN.name();
        } else if (le instanceof NullLiteralExpr) {
            typeFqn = null;
            canonicalValue = "null";
            literalType = LiteralType.NULL.name();
        } else {
            // TextBlockLiteralExpr and any future subtypes
            typeFqn = "java.lang.String";
            canonicalValue = le.toString();
            literalType = LiteralType.STRING.name();
        }

        var attrs = new HashMap<String, String>();
        attrs.put("literalType", literalType);
        attrs.put("value", canonicalValue);
        var node = mkExpr(FlowNodeKind.LITERAL, lineOf(le), typeFqn, attrs);
        return node;
    }

    // ─── Task 6: Array, Ternary, Lambda, MethodRef, ObjectCreation, Super ─

    private FlowNode analyzeArrayAccess(ArrayAccessExpr aae) {
        var arr = analyzeExpr(aae.getName());
        var idx = analyzeExpr(aae.getIndex());
        var node = mkExpr(FlowNodeKind.ARRAY_ACCESS, lineOf(aae), null, Map.of());
        if (arr != null) graph.addEdge(arr, node, FlowEdgeKind.ARRAY_REF);
        if (idx != null) graph.addEdge(idx, node, FlowEdgeKind.ARRAY_INDEX);
        return node;
    }

    private FlowNode analyzeArrayCreation(ArrayCreationExpr ace) {
        var elementType = ace.getElementType().asString();
        var attrs = new HashMap<String, String>();
        attrs.put("elementType", elementType);
        var node = mkExpr(FlowNodeKind.ARRAY_CREATE, lineOf(ace), elementType + "[]", attrs);
        for (var level : ace.getLevels()) {
            level.getDimension().ifPresent(dim -> {
                var dimNode = analyzeExpr(dim);
                if (dimNode != null) graph.addEdge(dimNode, node, FlowEdgeKind.ARRAY_DIM);
            });
        }
        // Also handle initializer if present
        ace.getInitializer().ifPresent(init -> {
            for (var val : init.getValues()) {
                var valNode = analyzeExpr(val);
                if (valNode != null) graph.addEdge(valNode, node, FlowEdgeKind.DATA_DEP);
            }
        });
        return node;
    }

    private FlowNode analyzeTernary(ConditionalExpr ce) {
        var cond = analyzeExpr(ce.getCondition());
        var thenVal = analyzeExpr(ce.getThenExpr());
        var elseVal = analyzeExpr(ce.getElseExpr());

        String resultType = thenVal != null ? thenVal.typeFqn() : null;
        var node = mkExpr(FlowNodeKind.TERNARY, lineOf(ce), resultType, Map.of());
        if (cond != null) graph.addEdge(cond, node, FlowEdgeKind.TERNARY_CONDITION);
        if (thenVal != null) graph.addEdge(thenVal, node, FlowEdgeKind.TERNARY_THEN);
        if (elseVal != null) graph.addEdge(elseVal, node, FlowEdgeKind.TERNARY_ELSE);
        return node;
    }

    private FlowNode analyzeLambda(LambdaExpr le) {
        var paramNames = new StringBuilder();
        var paramTypes = new StringBuilder();
        for (var p : le.getParameters()) {
            if (paramNames.length() > 0) {
                paramNames.append(",");
                paramTypes.append(",");
            }
            paramNames.append(p.getNameAsString());
            paramTypes.append(p.getType().asString());
        }
        var attrs = new HashMap<String, String>();
        attrs.put("paramNames", paramNames.toString());
        attrs.put("paramTypes", paramTypes.toString());
        return mkExpr(FlowNodeKind.LAMBDA, lineOf(le), null, attrs);
    }

    private FlowNode analyzeMethodReference(MethodReferenceExpr mre) {
        String targetType;
        if (mre.getScope() instanceof TypeExpr te) {
            targetType = te.getType().asString();
        } else if (mre.getScope() instanceof NameExpr ne) {
            targetType = ne.getNameAsString();
        } else {
            targetType = mre.getScope().toString();
        }
        var attrs = new HashMap<String, String>();
        attrs.put("methodName", mre.getIdentifier());
        attrs.put("targetType", targetType);
        return mkExpr(FlowNodeKind.METHOD_REF, lineOf(mre), null, attrs);
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private FlowNode ensureThisRef() {
        if (thisRef == null) {
            thisRef = mkNode(FlowNodeKind.THIS_REF, "this", -1, declaringTypeFqn,
                    null, -1, null, null, null, ControlSubtype.NONE);
        }
        return thisRef;
    }

    private FlowNode ensureSuperRef() {
        if (superRef == null) {
            superRef = new FlowNode(graph.nextId(prefixOf(FlowNodeKind.SUPER_REF)),
                    FlowNodeKind.SUPER_REF, "super", -1, declaringTypeFqn,
                    null, -1, null, null, null, ControlSubtype.NONE,
                    currentEnclosing(), null, -1);
            graph.addNode(superRef);
        }
        return superRef;
    }

    /**
     * Creates a typed expression sub-node using the 14-parameter FlowNode constructor.
     * The stmtOrdinal is assigned from the monotonically increasing stmtCounter.
     */
    private FlowNode mkExpr(FlowNodeKind kind, int line, String typeFqn,
                            Map<String, String> attributes) {
        var id = graph.nextId(prefixOf(kind));
        var node = new FlowNode(id, kind, "", line, typeFqn, null, -1,
                null, null, null, ControlSubtype.NONE, currentEnclosing(),
                attributes.isEmpty() ? null : new HashMap<>(attributes), stmtCounter++);
        graph.addNode(node);
        return node;
    }

    /**
     * Legacy bridge for expressions that don't yet have a dedicated handler.
     * Uses BINARY_OP kind as a placeholder.
     */
    private FlowNode mkTemp(String label, String typeFqn, FlowNode... sources) {
        var temp = mkNode(FlowNodeKind.BINARY_OP, label, -1, typeFqn,
                null, -1, null, null, null, ControlSubtype.NONE);
        for (var s : sources) {
            if (s != null) graph.addEdge(s, temp, FlowEdgeKind.DATA_DEP);
        }
        return temp;
    }

    private FlowNode mkControl(FlowNodeKind kind, ControlSubtype subtype, String label) {
        return mkNode(kind, label, -1, null, null, -1, null, null, null, subtype);
    }

    private FlowNode mkNode(FlowNodeKind kind, String label, int line,
                            String typeFqn, String varName, int varVersion,
                            MethodSignature sig, CallResolution res,
                            FieldOrigin origin, ControlSubtype subtype) {
        var node = new FlowNode(graph.nextId(prefixOf(kind)), kind, label, line,
                typeFqn, varName, varVersion, sig, res, origin, subtype, currentEnclosing());
        graph.addNode(node);
        return node;
    }

    private String prefixOf(FlowNodeKind kind) {
        return switch (kind) {
            case PARAM -> "param";
            case THIS_REF -> "this";
            case SUPER_REF -> "super";
            case FIELD_READ -> "fr";
            case FIELD_WRITE -> "fw";
            case LOCAL_DEF -> "def";
            case LOCAL_USE -> "use";
            case MERGE_VALUE -> "phi";
            case CALL -> "call";
            case CALL_RESULT -> "res";
            case RETURN -> "ret";
            case BRANCH -> "br";
            case MERGE -> "mrg";
            case LOOP -> "loop";
            case LITERAL -> "lit";
            case BINARY_OP -> "binop";
            case UNARY_OP -> "unop";
            case CAST -> "cast";
            case INSTANCEOF -> "iof";
            case ARRAY_CREATE -> "arrcr";
            case ARRAY_ACCESS -> "arrac";
            case TERNARY -> "tern";
            case OBJECT_CREATE -> "objcr";
            case LAMBDA -> "lam";
            case METHOD_REF -> "mref";
            case ASSIGN -> "asgn";
            case THROW -> "throw";
            case BREAK -> "brk";
            case CONTINUE -> "cont";
            case ASSERT -> "assert";
            case SYNCHRONIZED -> "sync";
            case SWITCH_CASE -> "case";
            case YIELD -> "yield";
        };
    }

    private String currentEnclosing() {
        return enclosingControlStack.isEmpty() ? null : enclosingControlStack.peek();
    }

    private static int lineOf(com.github.javaparser.ast.Node n) {
        return n.getBegin().map(p -> p.line).orElse(-1);
    }

    private MethodSignature signatureOf(MethodDeclaration md) {
        var paramTypes = new ArrayList<String>();
        for (var p : md.getParameters()) paramTypes.add(p.getType().asString());
        return new MethodSignature(declaringTypeFqn, md.getNameAsString(),
                paramTypes, md.getType().asString());
    }

    private MethodSignature signatureOf(ConstructorDeclaration cd) {
        var paramTypes = new ArrayList<String>();
        for (var p : cd.getParameters()) paramTypes.add(p.getType().asString());
        return new MethodSignature(declaringTypeFqn, "<init>", paramTypes, declaringTypeFqn);
    }
}
