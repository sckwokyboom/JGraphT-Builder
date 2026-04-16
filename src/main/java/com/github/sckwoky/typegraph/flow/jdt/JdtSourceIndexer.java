package com.github.sckwoky.typegraph.flow.jdt;

import com.github.sckwoky.typegraph.flow.spi.*;
import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * {@link SourceIndexer} implementation that uses the Eclipse JDT ASTParser.
 *
 * <p>For every {@code .java} file found under the given source roots, the
 * indexer parses the file, visits all {@link TypeDeclaration} nodes, and
 * extracts class/interface FQNs, methods, constructors, and fields into
 * {@link ClassInfo} records collected in a {@link ProjectIndex}.
 */
public class JdtSourceIndexer implements SourceIndexer {

    private final JdtEnvironment env;

    public JdtSourceIndexer(JdtEnvironment env) {
        this.env = env;
    }

    @Override
    public ProjectIndex indexProject(List<Path> sourceRoots) {
        Map<String, ClassInfo> byFqn = new LinkedHashMap<>();
        Map<Path, List<ClassInfo>> byFile = new LinkedHashMap<>();

        for (Path root : sourceRoots) {
            List<Path> javaFiles = collectJavaFiles(root);
            for (Path file : javaFiles) {
                try {
                    List<ClassInfo> classes = parseFile(file);
                    for (ClassInfo ci : classes) {
                        byFqn.put(ci.fqn(), ci);
                    }
                    byFile.put(file, classes);
                } catch (Exception e) {
                    System.err.println("JdtSourceIndexer: failed to index " + file + ": " + e.getMessage());
                }
            }
        }

        return new ProjectIndex(Collections.unmodifiableMap(byFqn),
                                Collections.unmodifiableMap(byFile));
    }

    // ─── File discovery ──────────────────────────────────────────────────────

    private static List<Path> collectJavaFiles(Path root) {
        if (!Files.isDirectory(root)) return List.of();
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            System.err.println("JdtSourceIndexer: cannot walk " + root + ": " + e.getMessage());
            return List.of();
        }
    }

    // ─── Parsing ─────────────────────────────────────────────────────────────

    private List<ClassInfo> parseFile(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        char[] source = new String(bytes, StandardCharsets.UTF_8).toCharArray();

        ASTParser parser = env.newParser();
        parser.setUnitName(file.getFileName().toString());
        parser.setSource(source);

        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        return extractClasses(cu, file);
    }

    // ─── Extraction ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<ClassInfo> extractClasses(CompilationUnit cu, Path file) {
        List<ClassInfo> result = new ArrayList<>();
        for (Object typeObj : cu.types()) {
            if (typeObj instanceof TypeDeclaration td) {
                collectType(td, cu, file, result);
            }
        }
        return result;
    }

    /** Recursively collect a type and its nested member types. */
    @SuppressWarnings("unchecked")
    private void collectType(TypeDeclaration td, CompilationUnit cu, Path file,
                             List<ClassInfo> out) {
        String fqn = resolveFqn(td);
        if (fqn == null || fqn.isEmpty()) return;

        int startLine = cu.getLineNumber(td.getStartPosition());
        int endLine   = cu.getLineNumber(td.getStartPosition() + td.getLength() - 1);

        List<ExecutableInfo> executables = new ArrayList<>();
        List<FieldInfo>      fields      = new ArrayList<>();

        // Fields
        for (FieldDeclaration fd : td.getFields()) {
            String typeName = fd.getType().toString();
            for (Object fragObj : fd.fragments()) {
                if (fragObj instanceof VariableDeclarationFragment vdf) {
                    fields.add(new FieldInfo(vdf.getName().getIdentifier(), typeName));
                }
            }
        }

        // Methods and constructors — skip abstract/interface methods (no body)
        for (MethodDeclaration md : td.getMethods()) {
            if (md.getBody() == null) continue;  // abstract or native — no body to analyze
            List<ParamInfo> params = buildParams(md);
            int mStart = cu.getLineNumber(md.getStartPosition());
            int mEnd   = cu.getLineNumber(md.getStartPosition() + md.getLength() - 1);

            if (md.isConstructor()) {
                executables.add(new ExecutableInfo(
                        ExecutableKind.CONSTRUCTOR, "<init>", fqn,
                        params, null,
                        file, mStart, mEnd));
            } else {
                String returnType = md.getReturnType2() != null
                        ? md.getReturnType2().toString()
                        : "void";
                executables.add(new ExecutableInfo(
                        ExecutableKind.METHOD, md.getName().getIdentifier(), fqn,
                        params, returnType,
                        file, mStart, mEnd));
            }
        }

        out.add(new ClassInfo(fqn, file, startLine, endLine, executables, fields));

        // Recurse into member types
        for (TypeDeclaration member : td.getTypes()) {
            collectType(member, cu, file, out);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ParamInfo> buildParams(MethodDeclaration md) {
        List<ParamInfo> params = new ArrayList<>();
        for (Object paramObj : md.parameters()) {
            if (paramObj instanceof SingleVariableDeclaration svd) {
                params.add(new ParamInfo(
                        svd.getName().getIdentifier(),
                        svd.getType().toString()));
            }
        }
        return params;
    }

    // ─── FQN resolution ──────────────────────────────────────────────────────

    private static String resolveFqn(TypeDeclaration td) {
        // Prefer binding-based FQN (requires setResolveBindings=true)
        ITypeBinding binding = td.resolveBinding();
        if (binding != null) {
            String qn = binding.getQualifiedName();
            if (qn != null && !qn.isEmpty()) return qn;
        }
        // Fallback: walk the AST to build FQN from package + enclosing types + simple name
        return buildFqnFromAst(td);
    }

    private static String buildFqnFromAst(TypeDeclaration td) {
        // Collect simple names from innermost to outermost type
        List<String> parts = new ArrayList<>();
        ASTNode node = td;
        while (node instanceof TypeDeclaration current) {
            parts.add(0, current.getName().getIdentifier());
            node = current.getParent();
        }
        // node is now the CompilationUnit (or something else if deeply nested)
        if (node instanceof CompilationUnit cu) {
            PackageDeclaration pkg = cu.getPackage();
            if (pkg != null) {
                parts.add(0, pkg.getName().getFullyQualifiedName());
            }
        }
        return String.join(".", parts);
    }
}
