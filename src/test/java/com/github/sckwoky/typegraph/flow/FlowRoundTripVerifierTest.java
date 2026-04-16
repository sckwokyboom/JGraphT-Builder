package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlowRoundTripVerifierTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @BeforeAll
    static void setUp() {
        var typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(FIXTURES));
        var config = new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver));
        StaticJavaParser.setConfiguration(config);
    }

    @Test
    void batchVerificationProducesReport() {
        var verifier = new FlowRoundTripVerifier();
        var report = verifier.verifyProject(List.of(FIXTURES));

        System.out.println("=== Round-trip Verification Report ===");
        System.out.printf("Total methods:   %d%n", report.totalMethods());
        System.out.printf("Parseable:       %d (%.1f%%)%n", report.parseable(),
                report.totalMethods() > 0 ? 100.0 * report.parseable() / report.totalMethods() : 0.0);
        System.out.printf("Average score:   %.3f%n", report.averageScore());
        System.out.println();

        if (!report.worstMethods().isEmpty()) {
            System.out.println("Worst methods:");
            for (var r : report.worstMethods()) {
                System.out.printf("  [%.3f] %s (parseable=%s, diffs=%d)%n",
                        r.similarityScore(), r.methodSignature(), r.parseable(), r.diffs().size());
                for (var diff : r.diffs()) {
                    System.out.println("    - " + diff);
                }
            }
        }

        // At least some methods should be present
        assertThat(report.totalMethods()).isGreaterThan(0);

        // Some methods should be parseable
        assertThat(report.parseable()).isGreaterThan(0);

        // Average score should be reasonable (> 0)
        assertThat(report.averageScore()).isGreaterThan(0.0);
    }
}
