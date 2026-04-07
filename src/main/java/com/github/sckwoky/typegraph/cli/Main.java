package com.github.sckwoky.typegraph.cli;

import com.github.sckwoky.typegraph.export.*;
import com.github.sckwoky.typegraph.graph.TypeGraphBuilder;
import com.github.sckwoky.typegraph.query.ChainFinder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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

    @Override
    public Integer call() throws Exception {
        System.out.println("Analyzing project: " + projectDir);

        var builder = new TypeGraphBuilder(projectDir).resolveJars(!noJars);
        var graph = builder.build();

        System.out.println(graph.summary());

        if (query != null) {
            return handleQuery(graph);
        }

        return handleExport(graph);
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
