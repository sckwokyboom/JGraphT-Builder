package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.sckwoky.typegraph.flow.jdt.JdtEnvironment;
import com.github.sckwoky.typegraph.flow.jdt.JdtSourceIndexer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlowGraphServiceTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private static List<ProjectFlowGraphs.Entry> oldEntries;
    private static List<FlowGraphService.Entry>  newEntries;

    @BeforeAll
    static void setUp() {
        // Configure JavaParser symbol solver (required by JavaParserMethodBodyAnalyzer)
        var typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(FIXTURES));
        var config = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        StaticJavaParser.setConfiguration(config);

        // Old path
        oldEntries = new ProjectFlowGraphs().buildAll(List.of(FIXTURES), t -> true);

        // New path: JdtSourceIndexer + JavaParserMethodBodyAnalyzer
        var jdtEnv    = new JdtEnvironment(List.of(FIXTURES), List.of());
        var indexer   = new JdtSourceIndexer(jdtEnv);
        var analyzer  = new JavaParserMethodBodyAnalyzer();
        var service   = new FlowGraphService(indexer, analyzer);
        newEntries = service.buildAll(List.of(FIXTURES), t -> true);
    }

    @Test
    void methodCountMatchesOldPath() {
        assertThat(newEntries)
                .as("FlowGraphService should produce the same number of flow graphs as ProjectFlowGraphs")
                .hasSameSizeAs(oldEntries);
    }

    @Test
    void noNullGraphs() {
        assertThat(newEntries)
                .extracting(FlowGraphService.Entry::graph)
                .doesNotContainNull();
    }

    @Test
    void declaringTypesMatch() {
        var oldTypes = oldEntries.stream()
                .map(ProjectFlowGraphs.Entry::declaringType)
                .sorted()
                .toList();
        var newTypes = newEntries.stream()
                .map(FlowGraphService.Entry::declaringType)
                .sorted()
                .toList();
        assertThat(newTypes).isEqualTo(oldTypes);
    }

    @Test
    void methodNamesMatch() {
        var oldNames = oldEntries.stream()
                .map(e -> e.declaringType() + "#" + e.methodName())
                .sorted()
                .toList();
        var newNames = newEntries.stream()
                .map(e -> e.declaringType() + "#" + e.methodName())
                .sorted()
                .toList();
        assertThat(newNames).isEqualTo(oldNames);
    }
}
