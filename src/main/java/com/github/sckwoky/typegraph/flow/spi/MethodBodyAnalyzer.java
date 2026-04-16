package com.github.sckwoky.typegraph.flow.spi;
import com.github.sckwoky.typegraph.flow.MethodFlowGraph;
import java.nio.file.Path;
import java.util.*;
public interface MethodBodyAnalyzer {
    MethodFlowGraph analyze(ExecutableInfo executable);
    default List<MethodFlowGraph> analyzeFile(Path file, List<ExecutableInfo> executables) {
        var results = new ArrayList<MethodFlowGraph>(executables.size());
        for (var exec : executables) {
            try { results.add(analyze(exec)); }
            catch (Exception e) {
                System.err.println("Failed to analyze " + exec.declaringType() + "#" + exec.name() + ": " + e.getMessage());
                results.add(null);
            }
        }
        return results;
    }
}
