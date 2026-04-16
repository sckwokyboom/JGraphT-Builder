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

class FlowCodeReconstructorTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");
    private static List<ProjectFlowGraphs.Entry> entries;

    @BeforeAll
    static void setUp() {
        var typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(FIXTURES));
        var config = new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver));
        StaticJavaParser.setConfiguration(config);

        entries = new ProjectFlowGraphs().buildAll(List.of(FIXTURES), t -> true);
    }

    private MethodFlowGraph findMethod(String declaring, String name) {
        return entries.stream()
                .filter(e -> e.declaringType().equals(declaring) && e.methodName().equals(name))
                .findFirst().orElseThrow().graph();
    }

    private static boolean isParseableAsMethod(String reconstructed) {
        try {
            String wrapped = "class __V {\n" + reconstructed + "\n}";
            StaticJavaParser.parse(wrapped);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void simpleReturnReconstructsParseably() {
        var graph = findMethod("com.example.ReconstructionFixture", "simpleReturn");
        var reconstructor = new FlowCodeReconstructor(graph);
        var code = reconstructor.reconstruct();
        System.out.println("=== simpleReturn ===");
        System.out.println(code);
        assertThat(isParseableAsMethod(code))
                .as("Reconstructed code should be parseable:\n%s", code)
                .isTrue();
    }

    @Test
    void methodCallReconstructsWithArguments() {
        var graph = findMethod("com.example.ReconstructionFixture", "methodCall");
        var reconstructor = new FlowCodeReconstructor(graph);
        var code = reconstructor.reconstruct();
        System.out.println("=== methodCall ===");
        System.out.println(code);
        assertThat(code).contains("adoptDog");
        assertThat(isParseableAsMethod(code))
                .as("Reconstructed code should be parseable:\n%s", code)
                .isTrue();
    }

    @Test
    void ifElseReconstructsWithCondition() {
        var graph = findMethod("com.example.ReconstructionFixture", "ifElse");
        var reconstructor = new FlowCodeReconstructor(graph);
        var code = reconstructor.reconstruct();
        System.out.println("=== ifElse ===");
        System.out.println(code);
        assertThat(code).contains("if");
        assertThat(isParseableAsMethod(code))
                .as("Reconstructed code should be parseable:\n%s", code)
                .isTrue();
    }

    @Test
    void whileLoopReconstructsWithCondition() {
        var graph = findMethod("com.example.ReconstructionFixture", "whileLoop");
        var reconstructor = new FlowCodeReconstructor(graph);
        var code = reconstructor.reconstruct();
        System.out.println("=== whileLoop ===");
        System.out.println(code);
        assertThat(code).contains("while");
        assertThat(isParseableAsMethod(code))
                .as("Reconstructed code should be parseable:\n%s", code)
                .isTrue();
    }

    @Test
    void ternaryReconstructsWithCondition() {
        var graph = findMethod("com.example.ReconstructionFixture", "ternary");
        var reconstructor = new FlowCodeReconstructor(graph);
        var code = reconstructor.reconstruct();
        System.out.println("=== ternary ===");
        System.out.println(code);
        assertThat(code).contains("?");
        assertThat(isParseableAsMethod(code))
                .as("Reconstructed code should be parseable:\n%s", code)
                .isTrue();
    }

    @Test
    void voidReturnReconstructs() {
        var graph = findMethod("com.example.ReconstructionFixture", "voidReturn");
        var reconstructor = new FlowCodeReconstructor(graph);
        var code = reconstructor.reconstruct();
        System.out.println("=== voidReturn ===");
        System.out.println(code);
        assertThat(code).contains("return;");
        assertThat(isParseableAsMethod(code))
                .as("Reconstructed code should be parseable:\n%s", code)
                .isTrue();
    }

    @Test
    void allFixtureMethodsProduceParseableCode() {
        var fixtureMethods = entries.stream()
                .filter(e -> e.declaringType().equals("com.example.ReconstructionFixture"))
                .toList();

        assertThat(fixtureMethods).isNotEmpty();

        for (var entry : fixtureMethods) {
            var reconstructor = new FlowCodeReconstructor(entry.graph());
            var code = reconstructor.reconstruct();
            System.out.println("=== " + entry.displayName() + " ===");
            System.out.println(code);
            assertThat(isParseableAsMethod(code))
                    .as("Method %s should produce parseable code:\n%s", entry.displayName(), code)
                    .isTrue();
        }
    }
}
