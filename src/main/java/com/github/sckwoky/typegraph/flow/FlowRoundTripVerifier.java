package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.StaticJavaParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Verifies round-trip fidelity: original source → flow graph → reconstructed source.
 * <p>
 * For each method, reconstructs code from the flow graph, checks parseability,
 * and computes a similarity score against the original source.
 */
public class FlowRoundTripVerifier {

    /**
     * Result of verifying a single method.
     */
    public record VerificationResult(
            String methodSignature,
            boolean parseable,
            List<String> diffs,
            double similarityScore,
            String originalCode,
            String reconstructedCode
    ) {}

    /**
     * Aggregate report for a batch of methods.
     */
    public record ProjectReport(
            int totalMethods,
            int parseable,
            double averageScore,
            List<VerificationResult> worstMethods
    ) {}

    /**
     * Verify a single method entry against its original source.
     *
     * @param entry          the flow graph entry
     * @param originalSource the original Java source code for the containing class
     * @return verification result
     */
    public VerificationResult verify(ProjectFlowGraphs.Entry entry, String originalSource) {
        var reconstructor = new FlowCodeReconstructor(entry.graph());
        String reconstructed = reconstructor.reconstruct();

        boolean parseable = isParseable(reconstructed);
        double similarity = computeSimilarity(originalSource, reconstructed);
        List<String> diffs = computeDiffs(originalSource, reconstructed);

        String sig = entry.displayName();
        return new VerificationResult(sig, parseable, diffs, similarity, originalSource, reconstructed);
    }

    /**
     * Batch-verify all methods found in the given source roots.
     *
     * @param sourceRoots directories containing Java source files
     * @return aggregate project report
     */
    public ProjectReport verifyProject(List<Path> sourceRoots) {
        var project = new ProjectFlowGraphs();
        var entries = project.buildAll(sourceRoots, t -> true);

        // Build a map from declaring type to source code
        var sourceMap = loadSourceMap(sourceRoots);

        var results = new ArrayList<VerificationResult>();
        for (var entry : entries) {
            String source = sourceMap.getOrDefault(entry.declaringType(), "");
            results.add(verify(entry, source));
        }

        int totalMethods = results.size();
        int parseableCount = (int) results.stream().filter(VerificationResult::parseable).count();
        double avgScore = results.stream().mapToDouble(VerificationResult::similarityScore).average().orElse(0.0);

        // Worst methods: lowest similarity, up to 10
        var worst = results.stream()
                .sorted(Comparator.comparingDouble(VerificationResult::similarityScore))
                .limit(10)
                .collect(Collectors.toList());

        return new ProjectReport(totalMethods, parseableCount, avgScore, worst);
    }

    // ─── Parseability ──────────────────────────────────────────────────

    private boolean isParseable(String reconstructedMethod) {
        try {
            String wrapped = "class __V {\n" + reconstructedMethod + "\n}";
            StaticJavaParser.parse(wrapped);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Similarity scoring ────────────────────────────────────────────

    /**
     * Compute a similarity score between original and reconstructed source.
     * <p>
     * Heuristic based on:
     * <ul>
     *   <li>Keyword matches (if/else/for/while/switch/try): 40%</li>
     *   <li>Call count match: 30%</li>
     *   <li>Operator count match: 20%</li>
     *   <li>Declaration count match: 10%</li>
     * </ul>
     *
     * @return score between 0.0 and 1.0
     */
    private double computeSimilarity(String original, String reconstructed) {
        double keywordScore = compareKeywords(original, reconstructed);
        double callScore = comparePatternCount(original, reconstructed, CALL_PATTERN);
        double operatorScore = comparePatternCount(original, reconstructed, OPERATOR_PATTERN);
        double declScore = comparePatternCount(original, reconstructed, DECL_PATTERN);

        return 0.40 * keywordScore + 0.30 * callScore + 0.20 * operatorScore + 0.10 * declScore;
    }

    private static final Set<String> KEYWORDS = Set.of(
            "if", "else", "for", "while", "switch", "try", "catch", "finally",
            "return", "throw", "break", "continue", "do", "synchronized"
    );

    private static final Pattern CALL_PATTERN = Pattern.compile("\\w+\\(");
    private static final Pattern OPERATOR_PATTERN = Pattern.compile("[+\\-*/=<>!&|^%]+");
    private static final Pattern DECL_PATTERN = Pattern.compile("\\b(int|long|double|float|boolean|char|byte|short|String|var|Object)\\s+\\w+");

    private double compareKeywords(String original, String reconstructed) {
        var origCounts = countKeywords(original);
        var recosCounts = countKeywords(reconstructed);

        if (origCounts.isEmpty() && recosCounts.isEmpty()) return 1.0;

        var allKeys = new HashSet<>(origCounts.keySet());
        allKeys.addAll(recosCounts.keySet());

        int matches = 0;
        int total = 0;
        for (var key : allKeys) {
            int origCount = origCounts.getOrDefault(key, 0);
            int recosCount = recosCounts.getOrDefault(key, 0);
            total += Math.max(origCount, recosCount);
            matches += Math.min(origCount, recosCount);
        }
        return total == 0 ? 1.0 : (double) matches / total;
    }

    private Map<String, Integer> countKeywords(String code) {
        var counts = new HashMap<String, Integer>();
        var words = code.split("\\W+");
        for (var word : words) {
            if (KEYWORDS.contains(word)) {
                counts.merge(word, 1, Integer::sum);
            }
        }
        return counts;
    }

    private double comparePatternCount(String original, String reconstructed, Pattern pattern) {
        int origCount = countMatches(original, pattern);
        int recosCount = countMatches(reconstructed, pattern);
        if (origCount == 0 && recosCount == 0) return 1.0;
        int max = Math.max(origCount, recosCount);
        int min = Math.min(origCount, recosCount);
        return (double) min / max;
    }

    private int countMatches(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    // ─── Diff computation ──────────────────────────────────────────────

    private List<String> computeDiffs(String original, String reconstructed) {
        var diffs = new ArrayList<String>();

        // Compare keyword counts
        var origKeywords = countKeywords(original);
        var recosKeywords = countKeywords(reconstructed);
        for (var kw : KEYWORDS) {
            int orig = origKeywords.getOrDefault(kw, 0);
            int recos = recosKeywords.getOrDefault(kw, 0);
            if (orig != recos) {
                diffs.add("keyword '" + kw + "': original=" + orig + " reconstructed=" + recos);
            }
        }

        // Compare call counts
        int origCalls = countMatches(original, CALL_PATTERN);
        int recosCalls = countMatches(reconstructed, CALL_PATTERN);
        if (origCalls != recosCalls) {
            diffs.add("call count: original=" + origCalls + " reconstructed=" + recosCalls);
        }

        return diffs;
    }

    // ─── Source loading ────────────────────────────────────────────────

    private Map<String, String> loadSourceMap(List<Path> sourceRoots) {
        var map = new HashMap<String, String>();
        for (var root : sourceRoots) {
            try (var files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> {
                            try {
                                String content = Files.readString(p);
                                // Extract package + class name from path
                                String relative = root.relativize(p).toString();
                                String fqn = relative.replace('/', '.')
                                        .replace('\\', '.')
                                        .replaceAll("\\.java$", "");
                                map.put(fqn, content);
                            } catch (IOException e) {
                                // skip
                            }
                        });
            } catch (IOException e) {
                // skip
            }
        }
        return map;
    }
}
