package com.github.sckwoky.typegraph.flow;

import com.github.sckwoky.typegraph.flow.spi.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/**
 * Parser-agnostic service that builds {@link MethodFlowGraph}s for an entire
 * project by composing a {@link SourceIndexer} (structural discovery) with a
 * {@link MethodBodyAnalyzer} (flow-graph construction).
 *
 * <p>This is the SPI-based replacement for {@link ProjectFlowGraphs}.
 */
public class FlowGraphService {

    /**
     * Mirrors the shape of {@link ProjectFlowGraphs.Entry} so existing
     * consumers can migrate with minimal changes.
     */
    public record Entry(
            String declaringType,
            String methodName,
            String displayName,
            String packageName,
            MethodFlowGraph graph
    ) {}

    private final SourceIndexer   indexer;
    private final MethodBodyAnalyzer analyzer;

    public FlowGraphService(SourceIndexer indexer, MethodBodyAnalyzer analyzer) {
        this.indexer   = indexer;
        this.analyzer  = analyzer;
    }

    /**
     * Indexes the project under {@code sourceRoots}, optionally filtered by
     * {@code scopePredicate} on the declaring-type FQN, then builds flow graphs.
     *
     * @param sourceRoots    directories to scan for {@code .java} files
     * @param scopePredicate filter on declaring-type FQN; pass {@code t -> true} for all
     * @return one {@link Entry} per successfully analyzed executable
     */
    public List<Entry> buildAll(List<Path> sourceRoots, Predicate<String> scopePredicate) {
        ProjectIndex index = indexer.indexProject(sourceRoots);

        // Group in-scope executables by file so the analyzer can parse each file once
        Map<Path, List<ExecutableInfo>> byFile = new LinkedHashMap<>();
        for (ExecutableInfo exec : index.allExecutables()) {
            if (!scopePredicate.test(exec.declaringType())) continue;
            byFile.computeIfAbsent(exec.file(), k -> new ArrayList<>()).add(exec);
        }

        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<Path, List<ExecutableInfo>> fileEntry : byFile.entrySet()) {
            Path file = fileEntry.getKey();
            List<ExecutableInfo> execs = fileEntry.getValue();
            List<MethodFlowGraph> graphs = analyzer.analyzeFile(file, execs);

            for (int i = 0; i < execs.size(); i++) {
                MethodFlowGraph graph = graphs.get(i);
                if (graph == null) continue;

                ExecutableInfo exec = execs.get(i);
                String simpleName = simpleNameOf(exec.declaringType());
                String displayName = simpleName + "#" + exec.name()
                        + "(" + formatParams(exec) + ")";
                entries.add(new Entry(
                        exec.declaringType(),
                        exec.name(),
                        displayName,
                        packageOf(exec.declaringType()),
                        graph));
            }
        }

        return entries;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String formatParams(ExecutableInfo exec) {
        if (exec.parameters() == null || exec.parameters().isEmpty()) return "";
        var sb = new StringBuilder();
        for (int i = 0; i < exec.parameters().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(exec.parameters().get(i).type());
        }
        return sb.toString();
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
