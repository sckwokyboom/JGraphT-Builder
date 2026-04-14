package com.github.sckwoky.typegraph.cli;

import com.github.sckwoky.typegraph.compose.*;
import com.github.sckwoky.typegraph.export.*;
import com.github.sckwoky.typegraph.flow.ProjectFlowGraphs;
import com.github.sckwoky.typegraph.graph.TypeGraphBuilder;
import com.github.sckwoky.typegraph.parsing.SourceScanner;
import com.github.sckwoky.typegraph.query.ChainFinder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "typegraph", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "Builds a type-relationship graph from a Java project and exports/queries it.")
public class Main implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the Java project to analyze")
    private Path projectDir;

    @Option(names = {"-o", "--output"}, description = "Output file path", defaultValue = "typegraph")
    private String output;

    @Option(names = {"-f", "--format"}, description = "Export format: dot, json, html (default: html)",
            defaultValue = "html")
    private String format;

    @Option(names = {"--no-jars"}, description = "Skip JAR dependency resolution")
    private boolean noJars;

    @Option(names = {"-q", "--query"}, description = "Query mode: find chains from input types to output type. " +
            "Format: inputType1,inputType2->outputType")
    private String query;

    @Option(names = {"--max-depth"}, description = "Maximum chain depth for queries (default: 5)",
            defaultValue = "5")
    private int maxDepth;

    @Option(names = {"--find-chains"}, description = "Composition retrieval: find candidate call chains for a target " +
            "method without body. Format: DeclaringType#methodName(Type1, Type2)->ReturnType")
    private String findChains;

    @Option(names = {"--top-k"}, description = "Top-K candidate chains to keep (default: 10)",
            defaultValue = "10")
    private int topK;

    @Option(names = {"--export-composition"},
            description = "Export the global composition graph (TYPE / METHOD / FIELD nodes) " +
                    "as html or json. Use with -o to set output path.")
    private boolean exportComposition;

    @Option(names = {"--flow-graphs"},
            description = "Build per-method intraprocedural flow graphs for all methods in the " +
                    "project (or filtered by --scope) and export them as an interactive HTML viewer.")
    private boolean flowGraphs;

    @Option(names = {"--scope"},
            description = "Restrict --flow-graphs / --find-chains to a class or package prefix " +
                    "(e.g. 'com.example' or 'com.example.OwnerHelper').")
    private String scope;

    @Override
    public Integer call() throws Exception {
        System.out.println("Analyzing project: " + projectDir);

        var builder = new TypeGraphBuilder(projectDir).resolveJars(!noJars);
        var graph = builder.build();

        System.out.println(graph.summary());

        if (flowGraphs) {
            return handleFlowGraphs();
        }

        if (findChains != null) {
            return handleFindChains(graph);
        }

        if (exportComposition) {
            return handleExportComposition(graph);
        }

        if (query != null) {
            return handleQuery(graph);
        }

        return handleExport(graph);
    }

    private int handleFlowGraphs() throws Exception {
        var sourceRoots = SourceScanner.detectSourceRoots(projectDir);
        var typeSolver = com.github.sckwoky.typegraph.parsing.TypeSolverFactory.create(
                sourceRoots,
                noJars ? java.util.List.<Path>of()
                        : com.github.sckwoky.typegraph.parsing.TypeSolverFactory.resolveGradleClasspath(projectDir));
        var config = new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setSymbolResolver(new com.github.javaparser.symbolsolver.JavaSymbolSolver(typeSolver));
        com.github.javaparser.StaticJavaParser.setConfiguration(config);

        java.util.function.Predicate<String> scopePredicate = scope == null || scope.isEmpty()
                ? t -> true
                : t -> t.equals(scope) || t.startsWith(scope + ".");

        var scanner = new ProjectFlowGraphs();
        var entries = scanner.buildAll(sourceRoots, scopePredicate);
        System.out.println("Built flow graphs for " + entries.size() + " methods" +
                (scope != null ? " (scope: " + scope + ")" : ""));
        if (entries.isEmpty()) {
            System.err.println("No methods to export. Try removing --scope or check the project path.");
            return 1;
        }

        String base = output == null || output.isEmpty() ? "flow" : output;
        Path outputDir = Path.of(base);
        new FlowHtmlExporter().export(entries, outputDir);
        System.out.println("Interactive flow viewer exported to: " + outputDir.resolve("index.html"));
        System.out.println("Open in browser: open " + outputDir.resolve("index.html"));
        return 0;
    }

    private int handleExportComposition(com.github.sckwoky.typegraph.graph.TypeGraph graph) throws Exception {
        var compositionGraph = new CompositionGraphBuilder().build(graph);
        System.out.println(compositionGraph.summary());

        String formatLower = format == null ? "html" : format.toLowerCase();
        String suffix = formatLower.equals("json") ? ".json" : ".html";
        String base = output == null || output.isEmpty() ? "composition" : output;
        Path outputPath = Path.of(base.contains(".") ? base : base + suffix);

        switch (formatLower) {
            case "json" -> {
                new CompositionJsonExporter().export(compositionGraph, outputPath);
                System.out.println("Composition graph (JSON) exported to: " + outputPath);
            }
            case "html" -> {
                new CompositionHtmlExporter().export(compositionGraph, outputPath);
                System.out.println("Interactive composition viewer exported to: " + outputPath);
                System.out.println("Open in browser: open " + outputPath);
            }
            default -> {
                System.err.println("Unsupported format for --export-composition: " + format +
                        " (use html or json)");
                return 1;
            }
        }
        return 0;
    }

    private int handleFindChains(com.github.sckwoky.typegraph.graph.TypeGraph graph) throws Exception {
        var sourceRoots = SourceScanner.detectSourceRoots(projectDir);

        var resolver = new TargetMethodSpecResolver();
        var target = resolver.resolve(findChains, sourceRoots);

        var compositionGraph = new CompositionGraphBuilder().build(graph);
        System.out.println(compositionGraph.summary());

        var matcher = new SignatureMatcher(graph);
        var engine = new ChainSearchEngine(matcher);
        var validator = new ExecutionPlanValidator(matcher,
                com.github.sckwoky.typegraph.compose.model.SubtypingPolicy.ALLOW_SUBTYPE);
        var ranker = new ChainRanker();
        var formatter = new ChainFormatter();

        var options = new ChainSearchEngine.SearchOptions(
                maxDepth,
                Math.max(topK * 5, 50),
                com.github.sckwoky.typegraph.compose.model.SubtypingPolicy.ALLOW_SUBTYPE,
                true
        );

        var raw = engine.search(target, compositionGraph, options);
        System.out.println("Raw candidates: " + raw.size());

        var validated = new java.util.ArrayList<com.github.sckwoky.typegraph.compose.model.CandidateChainInternal>();
        for (var c : raw) {
            var v = validator.validate(c);
            if (v.isValid()) validated.add(c.withValidation(v));
        }
        System.out.println("Validated candidates: " + validated.size());

        var ranked = validated.stream()
                .map(ranker::rank)
                .sorted((a, b) -> Double.compare(b.score().total(), a.score().total()))
                .limit(topK)
                .toList();

        String formatLower = format == null ? "prompt" : format.toLowerCase();

        // HTML mode: write a self-contained interactive viewer that combines the
        // composition graph with the ranked chains and lets the user click any
        // chain to highlight its path in the graph.
        if (formatLower.equals("html")) {
            var chainsJson = new ChainVisualizationBuilder().buildJson(ranked);
            String base = output == null || output.isEmpty() ? "chains" : output;
            Path outputPath = Path.of(base.contains(".") ? base : base + ".html");
            new CompositionHtmlExporter().export(compositionGraph, chainsJson, outputPath);
            System.out.println("Interactive chain viewer exported to: " + outputPath);
            System.out.println("Open in browser: open " + outputPath);
            // Also print a short text summary so users see the result in the terminal
            System.out.println();
            System.out.println(formatter.formatPrompt(target, ranked));
            return 0;
        }

        String content;
        switch (formatLower) {
            case "json" -> content = formatter.formatJson(target, ranked);
            case "prompt", "txt", "text" -> content = formatter.formatPrompt(target, ranked);
            default -> content = formatter.formatPrompt(target, ranked);
        }

        if (output != null && !output.isEmpty()) {
            String suffix = formatLower.equals("json") ? ".json" : ".txt";
            Path outputPath = Path.of(output.contains(".") ? output : output + suffix);
            Files.writeString(outputPath, content);
            System.out.println("Candidate chains written to: " + outputPath);
        } else {
            System.out.println();
            System.out.println(content);
        }
        return 0;
    }

    private int handleExport(com.github.sckwoky.typegraph.graph.TypeGraph graph) throws Exception {
        var outputPath = Path.of(output.contains(".") ? output : output + "." + format);

        switch (format.toLowerCase()) {
            case "dot" -> {
                new DotExporter().export(graph, outputPath);
                System.out.println("DOT graph exported to: " + outputPath);
                System.out.println("Render with: dot -Tsvg " + outputPath + " -o " + outputPath.toString().replace(".dot", ".svg"));
            }
            case "json" -> {
                new JsonExporter().export(graph, outputPath);
                System.out.println("JSON graph exported to: " + outputPath);
            }
            case "html" -> {
                new HtmlExporter().export(graph, outputPath);
                System.out.println("Interactive viewer exported to: " + outputPath);
                System.out.println("Open in browser: open " + outputPath);
            }
            default -> {
                System.err.println("Unknown format: " + format + ". Supported: dot, json, html");
                return 1;
            }
        }
        return 0;
    }

    private int handleQuery(com.github.sckwoky.typegraph.graph.TypeGraph graph) {
        // Parse query: inputType1,inputType2->outputType
        var parts = query.split("->");
        if (parts.length != 2) {
            System.err.println("Invalid query format. Expected: inputType1,inputType2->outputType");
            return 1;
        }

        var inputTypes = List.of(parts[0].strip().split(","));
        var outputType = parts[1].strip();

        System.out.println("Finding chains: " + inputTypes + " → " + outputType);
        System.out.println("Max depth: " + maxDepth);

        var finder = new ChainFinder(graph, maxDepth);
        var chains = finder.findChains(inputTypes, outputType);

        if (chains.isEmpty()) {
            System.out.println("No chains found.");
        } else {
            System.out.println("Found " + chains.size() + " chain(s):");
            for (int i = 0; i < chains.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + chains.get(i));
            }
        }
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
