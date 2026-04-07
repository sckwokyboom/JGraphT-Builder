package com.github.sckwoky.typegraph.parsing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers Java source files in a project directory.
 */
public final class SourceScanner {

    private SourceScanner() {}

    /**
     * Finds all {@code .java} source files under the given root directory.
     */
    public static List<Path> findJavaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + root, e);
        }
    }

    /**
     * Detects conventional source roots (e.g. src/main/java) under the project directory.
     * Falls back to the project directory itself if no conventional roots are found.
     */
    public static List<Path> detectSourceRoots(Path projectDir) {
        var conventional = List.of(
                projectDir.resolve("src/main/java"),
                projectDir.resolve("src/main/kotlin")
        );

        var found = conventional.stream()
                .filter(Files::isDirectory)
                .toList();

        return found.isEmpty() ? List.of(projectDir) : found;
    }
}
