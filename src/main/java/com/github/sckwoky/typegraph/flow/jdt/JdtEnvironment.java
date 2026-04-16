package com.github.sckwoky.typegraph.flow.jdt;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.JavaCore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates JDT {@link ASTParser} configuration for a project.
 *
 * <p>Each call to {@link #newParser()} produces a fresh, fully-configured
 * {@link ASTParser} ready to parse a single compilation unit with binding
 * resolution against the project's source roots and classpath.
 */
public class JdtEnvironment {

    private final String[] sourcePaths;
    private final String[] classpathPaths;

    public JdtEnvironment(List<Path> sourceRoots, List<Path> classpathEntries) {
        this.sourcePaths = sourceRoots.stream()
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .toArray(String[]::new);
        this.classpathPaths = classpathEntries.stream()
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .toArray(String[]::new);
    }

    /**
     * Creates a new {@link ASTParser} configured with binding resolution,
     * recovery, and Java 21 compiler options (the highest level supported
     * by JDT 3.40 without enabling experimental previews).
     */
    public ASTParser newParser() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);

        // source roots, encoding=null means default per entry, includeRunningVMBootclasspath=true
        parser.setEnvironment(classpathPaths, sourcePaths, null, true);

        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_21);
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_21);
        options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_21);
        parser.setCompilerOptions(options);

        return parser;
    }
}
