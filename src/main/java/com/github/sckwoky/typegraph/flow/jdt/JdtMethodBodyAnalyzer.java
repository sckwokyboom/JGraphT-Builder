package com.github.sckwoky.typegraph.flow.jdt;

import com.github.sckwoky.typegraph.flow.FieldIndex;
import com.github.sckwoky.typegraph.flow.MethodFlowGraph;
import com.github.sckwoky.typegraph.flow.VariableState;
import com.github.sckwoky.typegraph.flow.model.*;
import com.github.sckwoky.typegraph.flow.spi.*;
import com.github.sckwoky.typegraph.model.MethodSignature;
import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * {@link MethodBodyAnalyzer} implementation that uses Eclipse JDT Core AST
 * to build {@link MethodFlowGraph}s.
 *
 * <p>The algorithm mirrors {@link com.github.sckwoky.typegraph.flow.MethodFlowBuilder}
 * exactly; only the AST types differ (JDT instead of JavaParser).
 *
 * <h2>Documented limitations</h2>
 * <ul>
 *   <li>Loops are processed once; loop-carried dependencies are approximated
 *       by a single phi merge of pre- and post-iteration versions.</li>
 *   <li>Try/catch is conservatively over-approximated: every catch branch is
 *       reachable from the try entry.</li>
 *   <li>No alias analysis.</li>
 *   <li>Lambda bodies are opaque; the lambda itself becomes a LAMBDA node.</li>
 *   <li>Arrays are monolithic; element-wise tracking is not modeled.</li>
 * </ul>
 */
public class JdtMethodBodyAnalyzer implements MethodBodyAnalyzer {

    private final JdtEnvironment environment;

    public JdtMethodBodyAnalyzer(JdtEnvironment environment) {
        this.environment = environment;
    }

    // ─── SPI entry points ──────────────────────────────────────────────

    @Override
    public MethodFlowGraph analyze(ExecutableInfo executable) {
        try {
            CompilationUnit cu = parseFile(executable.file());
            return analyzeInCu(cu, executable);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to parse " + executable.file() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<MethodFlowGraph> analyzeFile(Path file, List<ExecutableInfo> executables) {
        var results = new ArrayList<MethodFlowGraph>(executables.size());
        CompilationUnit cu;
        try {
            cu = parseFile(file);
        } catch (IOException e) {
            System.err.println("JdtMethodBodyAnalyzer: failed to parse " + file + ": " + e.getMessage());
            for (int i = 0; i < executables.size(); i++) results.add(null);
            return results;
        }
        for (var exec : executables) {
            try {
                results.add(analyzeInCu(cu, exec));
            } catch (Exception e) {
                System.err.println("JdtMethodBodyAnalyzer: failed to analyze "
                        + exec.declaringType() + "#" + exec.name() + ": " + e.getMessage());
                results.add(null);
            }
        }
        return results;
    }

    // ─── Parsing ───────────────────────────────────────────────────────

    private CompilationUnit parseFile(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        char[] source = new String(bytes, StandardCharsets.UTF_8).toCharArray();
        ASTParser parser = environment.newParser();
        parser.setUnitName(file.getFileName().toString());
        parser.setSource(source);
        return (CompilationUnit) parser.createAST(null);
    }

    // ─── Analysis dispatch ─────────────────────────────────────────────

    private MethodFlowGraph analyzeInCu(CompilationUnit cu, ExecutableInfo exec) {
        TypeDeclaration td = findType(cu, exec.declaringType());
        if (td == null) {
            throw new IllegalArgumentException(
                    "Type not found in file: " + exec.declaringType());
        }
        FieldIndex fieldIndex = buildFieldIndex(td);
        MethodDeclaration md = findMethod(td, exec, cu);
        if (md == null) {
            throw new IllegalArgumentException(
                    "Method/constructor not found: " + exec.declaringType() + "#"
                    + exec.name() + " near line " + exec.startLine());
        }
        var builder = new Builder(exec.declaringType(), fieldIndex, cu);
        return builder.build(md, exec.kind());
    }

    // ─── Type/method lookup ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static TypeDeclaration findType(CompilationUnit cu, String declaringType) {
        // Try binding-based FQN first
        for (Object typeObj : cu.types()) {
            if (typeObj instanceof TypeDeclaration td) {
                TypeDeclaration found = findTypeRecursive(td, declaringType);
                if (found != null) return found;
            }
        }
        // Fallback: match simple name
        String simpleName = simpleNameOf(declaringType);
        for (Object typeObj : cu.types()) {
            if (typeObj instanceof TypeDeclaration td) {
                TypeDeclaration found = findTypeBySimpleName(td, simpleName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TypeDeclaration findTypeRecursive(TypeDeclaration td, String fqn) {
        String tdFqn = resolveFqn(td);
        if (fqn.equals(tdFqn)) return td;
        for (TypeDeclaration member : td.getTypes()) {
            TypeDeclaration found = findTypeRecursive(member, fqn);
            if (found != null) return found;
        }
        return null;
    }

    private static TypeDeclaration findTypeBySimpleName(TypeDeclaration td, String simpleName) {
        if (td.getName().getIdentifier().equals(simpleName)) return td;
        for (TypeDeclaration member : td.getTypes()) {
            TypeDeclaration found = findTypeBySimpleName(member, simpleName);
            if (found != null) return found;
        }
        return null;
    }

    private static String resolveFqn(TypeDeclaration td) {
        ITypeBinding binding = td.resolveBinding();
        if (binding != null) {
            String qn = binding.getQualifiedName();
            if (qn != null && !qn.isEmpty()) return qn;
        }
        // Fallback: walk AST to build FQN
        List<String> parts = new ArrayList<>();
        ASTNode node = td;
        while (node instanceof TypeDeclaration current) {
            parts.add(0, current.getName().getIdentifier());
            node = current.getParent();
        }
        if (node instanceof CompilationUnit cu) {
            PackageDeclaration pkg = cu.getPackage();
            if (pkg != null) {
                parts.add(0, pkg.getName().getFullyQualifiedName());
            }
        }
        return String.join(".", parts);
    }

    @SuppressWarnings("unchecked")
    private static MethodDeclaration findMethod(TypeDeclaration td, ExecutableInfo exec,
                                                CompilationUnit cu) {
        boolean isCtor = exec.kind() == ExecutableKind.CONSTRUCTOR;
        MethodDeclaration best = null;
        for (MethodDeclaration md : td.getMethods()) {
            if (md.getBody() == null) continue;
            if (isCtor && !md.isConstructor()) continue;
            if (!isCtor && md.isConstructor()) continue;
            if (!isCtor && !md.getName().getIdentifier().equals(exec.name())) continue;

            int line = cu.getLineNumber(md.getStartPosition());
            if (exec.startLine() >= 0 && line == exec.startLine()) return md;
            if (best == null) best = md;
        }
        return best;
    }

    private static FieldIndex buildFieldIndex(TypeDeclaration td) {
        var fields = new ArrayList<FieldInfo>();
        for (FieldDeclaration fd : td.getFields()) {
            String typeName = fd.getType().toString();
            for (Object fragObj : fd.fragments()) {
                if (fragObj instanceof VariableDeclarationFragment vdf) {
                    fields.add(new FieldInfo(vdf.getName().getIdentifier(), typeName));
                }
            }
        }
        return new FieldIndex(fields);
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Inner builder — mirrors MethodFlowBuilder but uses JDT AST types
    // ═══════════════════════════════════════════════════════════════════

    private static class Builder {

        private final String declaringTypeFqn;
        private final FieldIndex fields;
        private final CompilationUnit cu;
        private MethodFlowGraph graph;
        private VariableState scope;
        private final Deque<String> enclosingControlStack = new ArrayDeque<>();
        private FlowNode thisRef;
        private FlowNode superRef;
        private int stmtCounter = 0;

        Builder(String declaringTypeFqn, FieldIndex fields, CompilationUnit cu) {
            this.declaringTypeFqn = declaringTypeFqn;
            this.fields = fields;
            this.cu = cu;
        }

        // ─── Entry point ───────────────────────────────────────────────

        @SuppressWarnings("unchecked")
        MethodFlowGraph build(MethodDeclaration md, ExecutableKind kind) {
            var sig = signatureOf(md, kind);
            graph = new MethodFlowGraph(sig);
            scope = new VariableState();
            stmtCounter = 0;

            boolean isStatic = org.eclipse.jdt.core.dom.Modifier.isStatic(md.getModifiers());
            if (!isStatic) {
                thisRef = mkNode(FlowNodeKind.THIS_REF, "this", lineOf(md), declaringTypeFqn,
                        null, -1, null, null, null, ControlSubtype.NONE);
            }

            for (Object paramObj : md.parameters()) {
                if (paramObj instanceof SingleVariableDeclaration svd) {
                    String paramName = svd.getName().getIdentifier();
                    String paramType = svd.getType().toString();
                    var pn = mkNode(FlowNodeKind.PARAM, "param " + paramName,
                            lineOf(svd), paramType,
                            paramName, 0, null, null, null, ControlSubtype.NONE);
                    scope.define(paramName, pn);
                }
            }

            Block body = md.getBody();
            if (body != null) {
                processBlock(body);
            }
            return graph;
        }

        // ─── Statement dispatch ────────────────────────────────────────

        @SuppressWarnings("unchecked")
        private void processBlock(Block block) {
            scope.pushFrame();
            try {
                for (var s : (List<?>) block.statements()) {
                    if (s instanceof Statement stmt) {
                        processStmt(stmt);
                    }
                }
            } finally {
                scope.popFrame();
            }
        }

        private void processStmt(Statement s) {
            if (s instanceof ExpressionStatement es) {
                analyzeExpr(es.getExpression());
            } else if (s instanceof ReturnStatement rs) {
                processReturn(rs);
            } else if (s instanceof IfStatement is) {
                processIf(is);
            } else if (s instanceof Block bs) {
                processBlock(bs);
            } else if (s instanceof WhileStatement ws) {
                processWhile(ws);
            } else if (s instanceof DoStatement ds) {
                processDo(ds);
            } else if (s instanceof ForStatement fs) {
                processFor(fs);
            } else if (s instanceof EnhancedForStatement efs) {
                processForEach(efs);
            } else if (s instanceof TryStatement ts) {
                processTry(ts);
            } else if (s instanceof SwitchStatement ss) {
                processSwitch(ss);
            } else if (s instanceof ThrowStatement ths) {
                var throwNode = mkExpr(FlowNodeKind.THROW, lineOf(ths), null, Map.of());
                var val = analyzeExpr(ths.getExpression());
                if (val != null) graph.addEdge(val, throwNode, FlowEdgeKind.THROW_VALUE);
            } else if (s instanceof SynchronizedStatement ss) {
                var syncNode = mkExpr(FlowNodeKind.SYNCHRONIZED, lineOf(ss), null, Map.of());
                var lock = analyzeExpr(ss.getExpression());
                if (lock != null) graph.addEdge(lock, syncNode, FlowEdgeKind.SYNC_LOCK);
                enclosingControlStack.push(syncNode.id());
                processBlock(ss.getBody());
                enclosingControlStack.pop();
            } else if (s instanceof LabeledStatement ls) {
                enclosingControlStack.push("label:" + ls.getLabel().getIdentifier());
                processStmt(ls.getBody());
                enclosingControlStack.pop();
            } else if (s instanceof AssertStatement as) {
                var attrs = new HashMap<String, String>();
                var assertNode = mkExpr(FlowNodeKind.ASSERT, lineOf(as), null, attrs);
                var check = analyzeExpr(as.getExpression());
                if (check != null) graph.addEdge(check, assertNode, FlowEdgeKind.CONDITION);
                Expression msg = as.getMessage();
                if (msg != null) {
                    var msgNode = analyzeExpr(msg);
                    if (msgNode != null) graph.addEdge(msgNode, assertNode, FlowEdgeKind.ASSERT_MESSAGE);
                }
            } else if (s instanceof BreakStatement bs) {
                var attrs = new HashMap<String, String>();
                SimpleName label = bs.getLabel();
                if (label != null) attrs.put("targetLabel", label.getIdentifier());
                mkExpr(FlowNodeKind.BREAK, lineOf(bs), null, attrs);
            } else if (s instanceof ContinueStatement cs) {
                var attrs = new HashMap<String, String>();
                SimpleName label = cs.getLabel();
                if (label != null) attrs.put("targetLabel", label.getIdentifier());
                mkExpr(FlowNodeKind.CONTINUE, lineOf(cs), null, attrs);
            } else if (s instanceof VariableDeclarationStatement vds) {
                processVarDeclStmt(vds);
            }
            // EmptyStatement, TypeDeclarationStatement, etc. -> ignored
        }

        // ─── Control flow ──────────────────────────────────────────────

        private void processReturn(ReturnStatement rs) {
            var ret = mkNode(FlowNodeKind.RETURN, "return", lineOf(rs), null,
                    null, -1, null, null, null, ControlSubtype.NONE);
            Expression expr = rs.getExpression();
            if (expr != null) {
                var val = analyzeExpr(expr);
                if (val != null) graph.addEdge(val, ret, FlowEdgeKind.RETURN_DEP);
            }
        }

        private void processIf(IfStatement is) {
            var condVal = analyzeExpr(is.getExpression());
            var branch = mkControl(FlowNodeKind.BRANCH, ControlSubtype.IF, "if", lineOf(is));
            if (condVal != null) graph.addEdge(condVal, branch, FlowEdgeKind.CONDITION);

            var before = scope.snapshot();
            enclosingControlStack.push(branch.id());

            // then
            scope.pushFrame();
            processStmt(is.getThenStatement());
            var thenSnap = scope.snapshot();
            scope.popFrame();
            scope.restoreFromSnapshot(before);

            // else
            Map<String, FlowNode> elseSnap;
            Statement elseStmt = is.getElseStatement();
            if (elseStmt != null) {
                scope.pushFrame();
                processStmt(elseStmt);
                elseSnap = scope.snapshot();
                scope.popFrame();
                scope.restoreFromSnapshot(before);
            } else {
                elseSnap = before;
            }

            enclosingControlStack.pop();

            var merge = mkControl(FlowNodeKind.MERGE, ControlSubtype.IF, "merge", lineOf(is));
            graph.addEdge(branch, merge, FlowEdgeKind.CONTROL_DEP);
            mergeBranches(before, List.of(thenSnap, elseSnap), merge);
        }

        private void processWhile(WhileStatement ws) {
            var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.WHILE, "while", lineOf(ws));
            var cond = analyzeExpr(ws.getExpression());
            if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.CONDITION);

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

        private void processDo(DoStatement ds) {
            var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.DO, "do-while", lineOf(ds));
            var before = scope.snapshot();
            enclosingControlStack.push(loop.id());
            scope.pushFrame();
            processStmt(ds.getBody());
            var after = scope.snapshot();
            scope.popFrame();
            enclosingControlStack.pop();

            var cond = analyzeExpr(ds.getExpression());
            if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.CONDITION);

            scope.restoreFromSnapshot(before);
            mergeBranches(before, List.of(before, after), loop);
        }

        @SuppressWarnings("unchecked")
        private void processFor(ForStatement fs) {
            scope.pushFrame();
            var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.FOR, "for", lineOf(fs));
            for (Object initObj : fs.initializers()) {
                if (initObj instanceof Expression initExpr) {
                    var initNode = analyzeExpr(initExpr);
                    if (initNode != null) graph.addEdge(initNode, loop, FlowEdgeKind.LOOP_INIT);
                }
            }
            Expression compare = fs.getExpression();
            if (compare != null) {
                var cond = analyzeExpr(compare);
                if (cond != null) graph.addEdge(cond, loop, FlowEdgeKind.CONDITION);
            }

            var before = scope.snapshot();
            enclosingControlStack.push(loop.id());
            scope.pushFrame();
            processStmt(fs.getBody());
            for (Object updObj : fs.updaters()) {
                if (updObj instanceof Expression updExpr) {
                    var updNode = analyzeExpr(updExpr);
                    if (updNode != null) graph.addEdge(updNode, loop, FlowEdgeKind.LOOP_UPDATE);
                }
            }
            var after = scope.snapshot();
            scope.popFrame();
            enclosingControlStack.pop();
            scope.restoreFromSnapshot(before);
            mergeBranches(before, List.of(before, after), loop);

            scope.popFrame();
        }

        private void processForEach(EnhancedForStatement efs) {
            var loop = mkControl(FlowNodeKind.LOOP, ControlSubtype.FOREACH, "foreach", lineOf(efs));
            var iter = analyzeExpr(efs.getExpression());
            if (iter != null) graph.addEdge(iter, loop, FlowEdgeKind.LOOP_ITERABLE);

            var before = scope.snapshot();
            enclosingControlStack.push(loop.id());
            scope.pushFrame();

            SingleVariableDeclaration param = efs.getParameter();
            String varName = param.getName().getIdentifier();
            String varType = param.getType().toString();
            var loopVar = mkNode(FlowNodeKind.LOCAL_DEF, varName,
                    lineOf(efs), varType,
                    varName, scope.nextVersion(varName),
                    null, null, null, ControlSubtype.NONE);
            if (iter != null) graph.addEdge(iter, loopVar, FlowEdgeKind.DATA_DEP, "iter-elem");
            scope.define(varName, loopVar);

            processStmt(efs.getBody());
            var after = scope.snapshot();

            scope.popFrame();
            enclosingControlStack.pop();
            scope.restoreFromSnapshot(before);
            mergeBranches(before, List.of(before, after), loop);
        }

        @SuppressWarnings("unchecked")
        private void processTry(TryStatement ts) {
            var tryNode = mkControl(FlowNodeKind.BRANCH, ControlSubtype.TRY, "try", lineOf(ts));
            enclosingControlStack.push(tryNode.id());
            var before = scope.snapshot();

            scope.pushFrame();
            processBlock(ts.getBody());
            var trySnap = scope.snapshot();
            scope.popFrame();
            scope.restoreFromSnapshot(before);

            var allSnaps = new ArrayList<Map<String, FlowNode>>();
            allSnaps.add(trySnap);

            for (Object ccObj : ts.catchClauses()) {
                if (ccObj instanceof CatchClause cc) {
                    SingleVariableDeclaration catchParam = cc.getException();
                    String catchTypeName = catchParam.getType().toString();
                    var catchNode = mkControl(FlowNodeKind.BRANCH, ControlSubtype.CATCH,
                            "catch " + catchTypeName, lineOf(cc));
                    graph.addEdge(tryNode, catchNode, FlowEdgeKind.CONTROL_DEP);
                    enclosingControlStack.push(catchNode.id());
                    scope.pushFrame();

                    String pName = catchParam.getName().getIdentifier();
                    var catchParamNode = mkNode(FlowNodeKind.PARAM, "catch " + pName,
                            lineOf(catchParam), catchTypeName,
                            pName, 0, null, null, null, ControlSubtype.NONE);
                    scope.define(pName, catchParamNode);

                    processBlock(cc.getBody());
                    allSnaps.add(scope.snapshot());

                    scope.popFrame();
                    enclosingControlStack.pop();
                    scope.restoreFromSnapshot(before);
                }
            }

            enclosingControlStack.pop();

            var merge = mkControl(FlowNodeKind.MERGE, ControlSubtype.TRY, "try-merge", lineOf(ts));
            graph.addEdge(tryNode, merge, FlowEdgeKind.CONTROL_DEP);
            mergeBranches(before, allSnaps, merge);

            Block finallyBlock = ts.getFinally();
            if (finallyBlock != null) {
                var fin = mkControl(FlowNodeKind.BRANCH, ControlSubtype.FINALLY, "finally", lineOf(ts));
                graph.addEdge(merge, fin, FlowEdgeKind.CONTROL_DEP);
                enclosingControlStack.push(fin.id());
                scope.pushFrame();
                processBlock(finallyBlock);
                scope.popFrame();
                enclosingControlStack.pop();
            }
        }

        @SuppressWarnings("unchecked")
        private void processSwitch(SwitchStatement ss) {
            var sel = analyzeExpr(ss.getExpression());
            var branch = mkControl(FlowNodeKind.BRANCH, ControlSubtype.SWITCH, "switch", lineOf(ss));
            if (sel != null) graph.addEdge(sel, branch, FlowEdgeKind.CONDITION);

            var before = scope.snapshot();
            enclosingControlStack.push(branch.id());

            // Group statements by case entry
            var caseSnaps = new ArrayList<Map<String, FlowNode>>();
            var stmtGroup = new ArrayList<Statement>();
            boolean inCase = false;

            for (Object stmtObj : ss.statements()) {
                if (stmtObj instanceof SwitchCase) {
                    // Flush previous case group
                    if (inCase && !stmtGroup.isEmpty()) {
                        scope.pushFrame();
                        for (Statement stmt : stmtGroup) processStmt(stmt);
                        caseSnaps.add(scope.snapshot());
                        scope.popFrame();
                        scope.restoreFromSnapshot(before);
                        stmtGroup.clear();
                    }
                    inCase = true;
                } else if (stmtObj instanceof Statement stmt) {
                    stmtGroup.add(stmt);
                }
            }
            // Flush last group
            if (inCase && !stmtGroup.isEmpty()) {
                scope.pushFrame();
                for (Statement stmt : stmtGroup) processStmt(stmt);
                caseSnaps.add(scope.snapshot());
                scope.popFrame();
                scope.restoreFromSnapshot(before);
            }

            enclosingControlStack.pop();

            var merge = mkControl(FlowNodeKind.MERGE, ControlSubtype.SWITCH, "switch-merge", lineOf(ss));
            graph.addEdge(branch, merge, FlowEdgeKind.CONTROL_DEP);
            if (caseSnaps.isEmpty()) caseSnaps.add(before);
            mergeBranches(before, caseSnaps, merge);
        }

        @SuppressWarnings("unchecked")
        private void processVarDeclStmt(VariableDeclarationStatement vds) {
            String typeName = vds.getType().toString();
            for (Object fragObj : vds.fragments()) {
                if (fragObj instanceof VariableDeclarationFragment vdf) {
                    String name = vdf.getName().getIdentifier();
                    FlowNode init = vdf.getInitializer() != null
                            ? analyzeExpr(vdf.getInitializer())
                            : null;
                    var def = mkNode(FlowNodeKind.LOCAL_DEF, name + " :=", lineOf(vdf),
                            typeName,
                            name, scope.nextVersion(name), null, null, null, ControlSubtype.NONE);
                    if (init != null) graph.addEdge(init, def, FlowEdgeKind.DATA_DEP, "init");
                    scope.define(name, def);
                }
            }
        }

        // ─── Phi merging ──────────────────────────────────────────────

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

        // ─── Expression analysis ──────────────────────────────────────

        private FlowNode analyzeExpr(Expression expr) {
            if (expr == null) return null;

            if (expr instanceof SimpleName sn) return analyzeName(sn);
            if (expr instanceof QualifiedName qn) return analyzeQualifiedName(qn);
            if (expr instanceof FieldAccess fa) return analyzeFieldAccess(fa);
            if (expr instanceof ThisExpression) return ensureThisRef();
            if (expr instanceof SuperFieldAccess sfa) return analyzeSuperFieldAccess(sfa);
            if (expr instanceof SuperMethodInvocation smi) return analyzeSuperMethodInvocation(smi);
            if (expr instanceof MethodInvocation mi) return analyzeMethodInvocation(mi);
            if (expr instanceof ClassInstanceCreation cic) return analyzeClassInstanceCreation(cic);
            if (expr instanceof Assignment a) return analyzeAssignment(a);
            if (expr instanceof InfixExpression ie) return analyzeInfix(ie);
            if (expr instanceof PrefixExpression pe) return analyzePrefix(pe);
            if (expr instanceof PostfixExpression pe) return analyzePostfix(pe);
            if (expr instanceof CastExpression ce) return analyzeCast(ce);
            if (expr instanceof ParenthesizedExpression pe) return analyzeExpr(pe.getExpression());
            if (expr instanceof ConditionalExpression ce) return analyzeTernary(ce);
            if (expr instanceof InstanceofExpression ioe) return analyzeInstanceof(ioe);
            if (expr instanceof ArrayAccess aa) return analyzeArrayAccess(aa);
            if (expr instanceof ArrayCreation ac) return analyzeArrayCreation(ac);
            if (expr instanceof LambdaExpression le) return analyzeLambda(le);
            if (expr instanceof MethodReference mr) return analyzeMethodReference(mr);
            if (expr instanceof NumberLiteral
                    || expr instanceof StringLiteral
                    || expr instanceof CharacterLiteral
                    || expr instanceof BooleanLiteral
                    || expr instanceof NullLiteral
                    || expr instanceof TextBlock) {
                return analyzeLiteral(expr);
            }
            if (expr instanceof VariableDeclarationExpression vde) return analyzeVarDeclExpr(vde);
            // Fallback
            return mkTemp("expr:" + expr.getClass().getSimpleName(), null);
        }

        private FlowNode analyzeName(SimpleName sn) {
            String name = sn.getIdentifier();
            var current = scope.currentDef(name);
            if (current != null) return current;
            if (fields.contains(name)) {
                var fr = mkNode(FlowNodeKind.FIELD_READ, "this." + name, lineOf(sn),
                        fields.typeOf(name), name, -1, null, null,
                        FieldOrigin.THIS, ControlSubtype.NONE);
                if (thisRef != null) graph.addEdge(thisRef, fr, FlowEdgeKind.DATA_DEP, "this");
                return fr;
            }
            return mkTemp("ref:" + name, null);
        }

        private FlowNode analyzeQualifiedName(QualifiedName qn) {
            // qualifier.name -> treat as field access
            String fieldName = qn.getName().getIdentifier();
            Name qualifier = qn.getQualifier();

            FieldOrigin origin = FieldOrigin.OTHER;
            FlowNode receiver = null;

            if (qualifier instanceof SimpleName qualSimple) {
                String qualName = qualSimple.getIdentifier();
                if (Character.isUpperCase(qualName.charAt(0))
                        && scope.currentDef(qualName) == null) {
                    origin = FieldOrigin.STATIC;
                } else {
                    receiver = analyzeExpr(qualSimple);
                }
            } else {
                receiver = analyzeExpr(qualifier);
            }

            var fr = mkNode(FlowNodeKind.FIELD_READ, fieldName,
                    lineOf(qn), null, fieldName, -1, null, null, origin, ControlSubtype.NONE);
            if (receiver != null) graph.addEdge(receiver, fr, FlowEdgeKind.DATA_DEP, "receiver");
            return fr;
        }

        private FlowNode analyzeFieldAccess(FieldAccess fa) {
            FieldOrigin origin = FieldOrigin.OTHER;
            FlowNode receiver = null;
            Expression scope = fa.getExpression();
            if (scope instanceof ThisExpression) {
                origin = FieldOrigin.THIS;
                receiver = ensureThisRef();
            } else if (scope instanceof SimpleName neScope) {
                if (Character.isUpperCase(neScope.getIdentifier().charAt(0))
                        && this.scope.currentDef(neScope.getIdentifier()) == null) {
                    origin = FieldOrigin.STATIC;
                } else {
                    receiver = analyzeExpr(neScope);
                }
            } else {
                receiver = analyzeExpr(scope);
            }
            String fieldName = fa.getName().getIdentifier();
            var fr = mkNode(FlowNodeKind.FIELD_READ, fieldName,
                    lineOf(fa), null, fieldName, -1, null, null, origin, ControlSubtype.NONE);
            if (receiver != null) graph.addEdge(receiver, fr, FlowEdgeKind.DATA_DEP, "receiver");
            return fr;
        }

        private FlowNode analyzeSuperFieldAccess(SuperFieldAccess sfa) {
            var superNode = ensureSuperRef();
            String fieldName = sfa.getName().getIdentifier();
            var fr = mkNode(FlowNodeKind.FIELD_READ, "super." + fieldName,
                    lineOf(sfa), null, fieldName, -1, null, null, FieldOrigin.THIS, ControlSubtype.NONE);
            graph.addEdge(superNode, fr, FlowEdgeKind.DATA_DEP, "super");
            return fr;
        }

        @SuppressWarnings("unchecked")
        private FlowNode analyzeSuperMethodInvocation(SuperMethodInvocation smi) {
            var superNode = ensureSuperRef();

            var args = new ArrayList<FlowNode>();
            for (Object argObj : smi.arguments()) {
                if (argObj instanceof Expression argExpr) {
                    args.add(analyzeExpr(argExpr));
                }
            }

            String methodName = smi.getName().getIdentifier();
            MethodSignature sig = null;
            CallResolution res = CallResolution.UNRESOLVED;
            String returnType = null;
            IMethodBinding binding = smi.resolveMethodBinding();
            if (binding != null && !binding.isRecovered()) {
                returnType = binding.getReturnType().getQualifiedName();
                String declaring = binding.getDeclaringClass().getQualifiedName();
                var paramTypes = new ArrayList<String>();
                for (ITypeBinding pt : binding.getParameterTypes()) {
                    paramTypes.add(pt.getQualifiedName());
                }
                sig = new MethodSignature(declaring, methodName, paramTypes, returnType);
                res = CallResolution.RESOLVED;
            }

            var callAttrs = new HashMap<String, String>();
            callAttrs.put("methodName", methodName);
            callAttrs.put("callStyle", CallStyle.METHOD.name());

            var call = new FlowNode(graph.nextId(prefixOf(FlowNodeKind.CALL)),
                    FlowNodeKind.CALL, methodName + "()", lineOf(smi),
                    returnType, null, -1, sig, res, null, ControlSubtype.NONE,
                    currentEnclosing(), callAttrs, stmtCounter++);
            graph.addNode(call);

            graph.addEdge(superNode, call, FlowEdgeKind.RECEIVER);
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i) != null) {
                    graph.addEdge(args.get(i), call, FlowEdgeKind.ARG_PASS, String.valueOf(i));
                }
            }

            var result = mkNode(FlowNodeKind.CALL_RESULT, methodName + "->",
                    lineOf(smi), returnType, null, -1, sig, res, null, ControlSubtype.NONE);
            graph.addEdge(call, result, FlowEdgeKind.CALL_RESULT_OF);
            return result;
        }

        @SuppressWarnings("unchecked")
        private FlowNode analyzeMethodInvocation(MethodInvocation mi) {
            // Receiver
            FlowNode receiver = mi.getExpression() != null
                    ? analyzeExpr(mi.getExpression())
                    : null;

            // Args
            var args = new ArrayList<FlowNode>();
            for (Object argObj : mi.arguments()) {
                if (argObj instanceof Expression argExpr) {
                    args.add(analyzeExpr(argExpr));
                }
            }

            // Resolution
            String methodName = mi.getName().getIdentifier();
            MethodSignature sig = null;
            CallResolution res = CallResolution.UNRESOLVED;
            String returnType = null;
            String declaring = null;
            IMethodBinding binding = mi.resolveMethodBinding();
            if (binding != null && !binding.isRecovered()) {
                returnType = binding.getReturnType().getQualifiedName();
                declaring = binding.getDeclaringClass().getQualifiedName();
                var paramTypes = new ArrayList<String>();
                for (ITypeBinding pt : binding.getParameterTypes()) {
                    paramTypes.add(pt.getQualifiedName());
                }
                sig = new MethodSignature(declaring, methodName, paramTypes, returnType);
                res = CallResolution.RESOLVED;
            }

            // Determine call style
            String callStyle;
            if (mi.getExpression() == null) {
                callStyle = CallStyle.METHOD.name();
            } else if (receiver != null && receiver.kind() == FlowNodeKind.THIS_REF) {
                callStyle = CallStyle.METHOD.name();
            } else if (declaring != null && mi.getExpression() instanceof SimpleName nameScope
                    && Character.isUpperCase(nameScope.getIdentifier().charAt(0))
                    && scope.currentDef(nameScope.getIdentifier()) == null) {
                callStyle = CallStyle.STATIC.name();
            } else if (mi.getExpression() != null) {
                callStyle = CallStyle.CHAINED.name();
            } else {
                callStyle = CallStyle.METHOD.name();
            }

            var callAttrs = new HashMap<String, String>();
            callAttrs.put("methodName", methodName);
            callAttrs.put("callStyle", callStyle);

            var call = new FlowNode(graph.nextId(prefixOf(FlowNodeKind.CALL)),
                    FlowNodeKind.CALL, methodName + "()", lineOf(mi),
                    returnType, null, -1, sig, res, null, ControlSubtype.NONE,
                    currentEnclosing(), callAttrs, stmtCounter++);
            graph.addNode(call);

            if (receiver != null) graph.addEdge(receiver, call, FlowEdgeKind.RECEIVER);
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i) != null) {
                    graph.addEdge(args.get(i), call, FlowEdgeKind.ARG_PASS, String.valueOf(i));
                }
            }

            var result = mkNode(FlowNodeKind.CALL_RESULT, methodName + "->",
                    lineOf(mi), returnType, null, -1, sig, res, null, ControlSubtype.NONE);
            graph.addEdge(call, result, FlowEdgeKind.CALL_RESULT_OF);
            return result;
        }

        @SuppressWarnings("unchecked")
        private FlowNode analyzeClassInstanceCreation(ClassInstanceCreation cic) {
            var args = new ArrayList<FlowNode>();
            for (Object argObj : cic.arguments()) {
                if (argObj instanceof Expression argExpr) {
                    args.add(analyzeExpr(argExpr));
                }
            }

            String typeName = cic.getType().toString();
            MethodSignature sig = null;
            CallResolution res = CallResolution.UNRESOLVED;
            String returnType = typeName;
            IMethodBinding binding = cic.resolveConstructorBinding();
            if (binding != null && !binding.isRecovered()) {
                String declaring = binding.getDeclaringClass().getQualifiedName();
                returnType = declaring;
                var paramTypes = new ArrayList<String>();
                for (ITypeBinding pt : binding.getParameterTypes()) {
                    paramTypes.add(pt.getQualifiedName());
                }
                sig = new MethodSignature(declaring, "<init>", paramTypes, declaring);
                res = CallResolution.RESOLVED;
            }

            var objAttrs = new HashMap<String, String>();
            objAttrs.put("methodName", "<init>");
            objAttrs.put("callStyle", CallStyle.CONSTRUCTOR.name());

            var call = new FlowNode(graph.nextId(prefixOf(FlowNodeKind.OBJECT_CREATE)),
                    FlowNodeKind.OBJECT_CREATE, "new " + typeName, lineOf(cic),
                    returnType, null, -1, sig, res, null, ControlSubtype.NONE,
                    currentEnclosing(), objAttrs, stmtCounter++);
            graph.addNode(call);

            for (int i = 0; i < args.size(); i++) {
                if (args.get(i) != null) {
                    graph.addEdge(args.get(i), call, FlowEdgeKind.ARG_PASS, String.valueOf(i));
                }
            }
            var result = mkNode(FlowNodeKind.CALL_RESULT, typeName,
                    lineOf(cic), returnType, null, -1, sig, res, null, ControlSubtype.NONE);
            graph.addEdge(call, result, FlowEdgeKind.CALL_RESULT_OF);
            return result;
        }

        private FlowNode analyzeAssignment(Assignment a) {
            var rhs = analyzeExpr(a.getRightHandSide());
            Expression target = a.getLeftHandSide();

            if (target instanceof SimpleName sn) {
                String name = sn.getIdentifier();
                if (scope.currentDef(name) != null || !fields.contains(name)) {
                    var def = mkNode(FlowNodeKind.LOCAL_DEF, name + ":=", lineOf(a),
                            rhs != null ? rhs.typeFqn() : null,
                            name, scope.nextVersion(name), null, null, null, ControlSubtype.NONE);
                    if (rhs != null) graph.addEdge(rhs, def, FlowEdgeKind.DATA_DEP, "value");
                    scope.update(name, def);
                    return def;
                } else {
                    var fw = mkNode(FlowNodeKind.FIELD_WRITE, "this." + name + ":=",
                            lineOf(a), fields.typeOf(name), name, -1, null, null,
                            FieldOrigin.THIS, ControlSubtype.NONE);
                    if (thisRef != null) graph.addEdge(thisRef, fw, FlowEdgeKind.DATA_DEP, "this");
                    if (rhs != null) graph.addEdge(rhs, fw, FlowEdgeKind.DATA_DEP, "value");
                    return fw;
                }
            }

            if (target instanceof FieldAccess fa) {
                FieldOrigin origin = fa.getExpression() instanceof ThisExpression
                        ? FieldOrigin.THIS : FieldOrigin.OTHER;
                FlowNode receiver = fa.getExpression() instanceof ThisExpression
                        ? ensureThisRef() : analyzeExpr(fa.getExpression());
                String fieldName = fa.getName().getIdentifier();
                var fw = mkNode(FlowNodeKind.FIELD_WRITE, fieldName + ":=",
                        lineOf(a), null, fieldName, -1, null, null, origin, ControlSubtype.NONE);
                if (receiver != null) graph.addEdge(receiver, fw, FlowEdgeKind.DATA_DEP, "receiver");
                if (rhs != null) graph.addEdge(rhs, fw, FlowEdgeKind.DATA_DEP, "value");
                return fw;
            }

            if (target instanceof ArrayAccess aa) {
                var arr = analyzeExpr(aa.getArray());
                if (rhs != null && arr != null) graph.addEdge(rhs, arr, FlowEdgeKind.DATA_DEP, "elem");
                return rhs;
            }

            return rhs;
        }

        @SuppressWarnings("unchecked")
        private FlowNode analyzeVarDeclExpr(VariableDeclarationExpression vde) {
            FlowNode last = null;
            String typeName = vde.getType().toString();
            for (Object fragObj : vde.fragments()) {
                if (fragObj instanceof VariableDeclarationFragment vdf) {
                    String name = vdf.getName().getIdentifier();
                    FlowNode init = vdf.getInitializer() != null
                            ? analyzeExpr(vdf.getInitializer())
                            : null;
                    var def = mkNode(FlowNodeKind.LOCAL_DEF, name + " :=", lineOf(vdf),
                            typeName,
                            name, scope.nextVersion(name), null, null, null, ControlSubtype.NONE);
                    if (init != null) graph.addEdge(init, def, FlowEdgeKind.DATA_DEP, "init");
                    scope.define(name, def);
                    last = def;
                }
            }
            return last;
        }

        // ─── Binary, Unary, Cast, InstanceOf, Literal ─────────────────

        @SuppressWarnings("unchecked")
        private FlowNode analyzeInfix(InfixExpression ie) {
            var l = analyzeExpr(ie.getLeftOperand());
            var r = analyzeExpr(ie.getRightOperand());
            var op = BinaryOperator.fromJdt(ie.getOperator());
            String resultType = switch (op) {
                case AND, OR, EQ, NE, LT, GT, LE, GE -> "boolean";
                default -> null;
            };
            var attrs = new HashMap<String, String>();
            attrs.put("operator", op.name());
            var node = mkExpr(FlowNodeKind.BINARY_OP, lineOf(ie), resultType, attrs);
            if (l != null) graph.addEdge(l, node, FlowEdgeKind.LEFT_OPERAND);
            if (r != null) graph.addEdge(r, node, FlowEdgeKind.RIGHT_OPERAND);

            // Handle extended operands (e.g. a + b + c)
            if (ie.hasExtendedOperands()) {
                for (Object extObj : ie.extendedOperands()) {
                    if (extObj instanceof Expression extExpr) {
                        var extNode = analyzeExpr(extExpr);
                        if (extNode != null) {
                            // Create a new binary op chaining previous result with extended operand
                            var chainAttrs = new HashMap<String, String>();
                            chainAttrs.put("operator", op.name());
                            var chainNode = mkExpr(FlowNodeKind.BINARY_OP, lineOf(ie), resultType, chainAttrs);
                            graph.addEdge(node, chainNode, FlowEdgeKind.LEFT_OPERAND);
                            graph.addEdge(extNode, chainNode, FlowEdgeKind.RIGHT_OPERAND);
                            node = chainNode;
                        }
                    }
                }
            }

            return node;
        }

        private FlowNode analyzePrefix(PrefixExpression pe) {
            var inner = analyzeExpr(pe.getOperand());
            var op = UnaryOperator.fromJdtPrefix(pe.getOperator());
            var attrs = new HashMap<String, String>();
            attrs.put("operator", op.name());
            var node = mkExpr(FlowNodeKind.UNARY_OP, lineOf(pe),
                    inner != null ? inner.typeFqn() : null, attrs);
            if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.UNARY_OPERAND);
            return node;
        }

        private FlowNode analyzePostfix(PostfixExpression pe) {
            var inner = analyzeExpr(pe.getOperand());
            var op = UnaryOperator.fromJdtPostfix(pe.getOperator());
            var attrs = new HashMap<String, String>();
            attrs.put("operator", op.name());
            var node = mkExpr(FlowNodeKind.UNARY_OP, lineOf(pe),
                    inner != null ? inner.typeFqn() : null, attrs);
            if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.UNARY_OPERAND);
            return node;
        }

        private FlowNode analyzeCast(CastExpression ce) {
            var inner = analyzeExpr(ce.getExpression());
            String targetType = ce.getType().toString();
            var attrs = new HashMap<String, String>();
            attrs.put("targetType", targetType);
            var node = mkExpr(FlowNodeKind.CAST, lineOf(ce), targetType, attrs);
            if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.CAST_OPERAND);
            return node;
        }

        private FlowNode analyzeInstanceof(InstanceofExpression ioe) {
            var inner = analyzeExpr(ioe.getLeftOperand());
            var attrs = new HashMap<String, String>();
            attrs.put("targetType", ioe.getRightOperand().toString());
            var node = mkExpr(FlowNodeKind.INSTANCEOF, lineOf(ioe), "boolean", attrs);
            if (inner != null) graph.addEdge(inner, node, FlowEdgeKind.INSTANCEOF_OPERAND);
            return node;
        }

        private FlowNode analyzeLiteral(Expression expr) {
            String typeFqn;
            String canonicalValue;
            String literalType;

            if (expr instanceof NumberLiteral nl) {
                String token = nl.getToken();
                if (token.endsWith("L") || token.endsWith("l")) {
                    typeFqn = "long";
                    canonicalValue = token;
                    literalType = LiteralType.LONG.name();
                } else if (token.contains(".") || token.endsWith("f") || token.endsWith("F")
                        || token.endsWith("d") || token.endsWith("D")) {
                    typeFqn = "double";
                    canonicalValue = token;
                    literalType = LiteralType.DOUBLE.name();
                } else {
                    typeFqn = "int";
                    canonicalValue = token;
                    literalType = LiteralType.INT.name();
                }
            } else if (expr instanceof StringLiteral sl) {
                typeFqn = "java.lang.String";
                canonicalValue = sl.getLiteralValue();
                literalType = LiteralType.STRING.name();
            } else if (expr instanceof CharacterLiteral cl) {
                typeFqn = "char";
                canonicalValue = String.valueOf(cl.charValue());
                literalType = LiteralType.CHAR.name();
            } else if (expr instanceof BooleanLiteral bl) {
                typeFqn = "boolean";
                canonicalValue = String.valueOf(bl.booleanValue());
                literalType = LiteralType.BOOLEAN.name();
            } else if (expr instanceof NullLiteral) {
                typeFqn = null;
                canonicalValue = "null";
                literalType = LiteralType.NULL.name();
            } else if (expr instanceof TextBlock tb) {
                typeFqn = "java.lang.String";
                canonicalValue = tb.getLiteralValue();
                literalType = LiteralType.STRING.name();
            } else {
                typeFqn = "java.lang.String";
                canonicalValue = expr.toString();
                literalType = LiteralType.STRING.name();
            }

            var attrs = new HashMap<String, String>();
            attrs.put("literalType", literalType);
            attrs.put("value", canonicalValue);
            return mkExpr(FlowNodeKind.LITERAL, lineOf(expr), typeFqn, attrs);
        }

        // ─── Array, Ternary, Lambda, MethodRef ────────────────────────

        private FlowNode analyzeArrayAccess(ArrayAccess aa) {
            var arr = analyzeExpr(aa.getArray());
            var idx = analyzeExpr(aa.getIndex());
            var node = mkExpr(FlowNodeKind.ARRAY_ACCESS, lineOf(aa), null, Map.of());
            if (arr != null) graph.addEdge(arr, node, FlowEdgeKind.ARRAY_REF);
            if (idx != null) graph.addEdge(idx, node, FlowEdgeKind.ARRAY_INDEX);
            return node;
        }

        @SuppressWarnings("unchecked")
        private FlowNode analyzeArrayCreation(ArrayCreation ac) {
            String elementType = ac.getType().getElementType().toString();
            var attrs = new HashMap<String, String>();
            attrs.put("elementType", elementType);
            var node = mkExpr(FlowNodeKind.ARRAY_CREATE, lineOf(ac), elementType + "[]", attrs);
            for (Object dimObj : ac.dimensions()) {
                if (dimObj instanceof Expression dimExpr) {
                    var dimNode = analyzeExpr(dimExpr);
                    if (dimNode != null) graph.addEdge(dimNode, node, FlowEdgeKind.ARRAY_DIM);
                }
            }
            ArrayInitializer initializer = ac.getInitializer();
            if (initializer != null) {
                for (Object valObj : initializer.expressions()) {
                    if (valObj instanceof Expression valExpr) {
                        var valNode = analyzeExpr(valExpr);
                        if (valNode != null) graph.addEdge(valNode, node, FlowEdgeKind.DATA_DEP);
                    }
                }
            }
            return node;
        }

        private FlowNode analyzeTernary(ConditionalExpression ce) {
            var cond = analyzeExpr(ce.getExpression());
            var thenVal = analyzeExpr(ce.getThenExpression());
            var elseVal = analyzeExpr(ce.getElseExpression());

            String resultType = thenVal != null ? thenVal.typeFqn() : null;
            var node = mkExpr(FlowNodeKind.TERNARY, lineOf(ce), resultType, Map.of());
            if (cond != null) graph.addEdge(cond, node, FlowEdgeKind.TERNARY_CONDITION);
            if (thenVal != null) graph.addEdge(thenVal, node, FlowEdgeKind.TERNARY_THEN);
            if (elseVal != null) graph.addEdge(elseVal, node, FlowEdgeKind.TERNARY_ELSE);
            return node;
        }

        @SuppressWarnings("unchecked")
        private FlowNode analyzeLambda(LambdaExpression le) {
            var paramNames = new StringBuilder();
            var paramTypes = new StringBuilder();
            for (Object paramObj : le.parameters()) {
                if (paramObj instanceof VariableDeclaration vd) {
                    if (paramNames.length() > 0) {
                        paramNames.append(",");
                        paramTypes.append(",");
                    }
                    paramNames.append(vd.getName().getIdentifier());
                    if (vd instanceof SingleVariableDeclaration svd) {
                        paramTypes.append(svd.getType().toString());
                    } else {
                        paramTypes.append("?");
                    }
                }
            }
            var attrs = new HashMap<String, String>();
            attrs.put("paramNames", paramNames.toString());
            attrs.put("paramTypes", paramTypes.toString());
            return mkExpr(FlowNodeKind.LAMBDA, lineOf(le), null, attrs);
        }

        private FlowNode analyzeMethodReference(MethodReference mr) {
            var attrs = new HashMap<String, String>();
            if (mr instanceof ExpressionMethodReference emr) {
                attrs.put("methodName", emr.getName().getIdentifier());
                attrs.put("targetType", emr.getExpression().toString());
            } else if (mr instanceof TypeMethodReference tmr) {
                attrs.put("methodName", tmr.getName().getIdentifier());
                attrs.put("targetType", tmr.getType().toString());
            } else if (mr instanceof SuperMethodReference smr) {
                attrs.put("methodName", smr.getName().getIdentifier());
                attrs.put("targetType", "super");
            } else if (mr instanceof CreationReference cr) {
                attrs.put("methodName", "<init>");
                attrs.put("targetType", cr.getType().toString());
            }
            return mkExpr(FlowNodeKind.METHOD_REF, lineOf(mr), null, attrs);
        }

        // ─── Helpers ──────────────────────────────────────────────────

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

        private FlowNode mkExpr(FlowNodeKind kind, int line, String typeFqn,
                                Map<String, String> attributes) {
            var id = graph.nextId(prefixOf(kind));
            var node = new FlowNode(id, kind, "", line, typeFqn, null, -1,
                    null, null, null, ControlSubtype.NONE, currentEnclosing(),
                    attributes.isEmpty() ? null : new HashMap<>(attributes), stmtCounter++);
            graph.addNode(node);
            return node;
        }

        private FlowNode mkTemp(String label, String typeFqn, FlowNode... sources) {
            var temp = mkNode(FlowNodeKind.BINARY_OP, label, -1, typeFqn,
                    null, -1, null, null, null, ControlSubtype.NONE);
            for (var s : sources) {
                if (s != null) graph.addEdge(s, temp, FlowEdgeKind.DATA_DEP);
            }
            return temp;
        }

        private FlowNode mkControl(FlowNodeKind kind, ControlSubtype subtype,
                                    String label, int line) {
            return mkNode(kind, label, line, null, null, -1, null, null, null, subtype);
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

        private int lineOf(ASTNode n) {
            return cu.getLineNumber(n.getStartPosition());
        }

        @SuppressWarnings("unchecked")
        private MethodSignature signatureOf(MethodDeclaration md, ExecutableKind kind) {
            var paramTypes = new ArrayList<String>();
            for (Object paramObj : md.parameters()) {
                if (paramObj instanceof SingleVariableDeclaration svd) {
                    paramTypes.add(svd.getType().toString());
                }
            }
            if (kind == ExecutableKind.CONSTRUCTOR) {
                return new MethodSignature(declaringTypeFqn, "<init>",
                        paramTypes, declaringTypeFqn);
            } else {
                String returnType = md.getReturnType2() != null
                        ? md.getReturnType2().toString()
                        : "void";
                return new MethodSignature(declaringTypeFqn, md.getName().getIdentifier(),
                        paramTypes, returnType);
            }
        }
    }
}
