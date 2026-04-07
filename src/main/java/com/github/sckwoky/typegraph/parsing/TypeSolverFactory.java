package com.github.sckwoky.typegraph.parsing;

import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * Creates a combined {@link com.github.javaparser.resolution.TypeSolver} for a project,
 * including source roots, JDK types (via reflection), and JAR dependencies.
 */
public final class TypeSolverFactory {

    private TypeSolverFactory() {}

    /**
     * Creates a type solver that resolves types from:
     * <ol>
     *   <li>Project source roots</li>
     *   <li>JDK classes (reflection)</li>
     *   <li>JAR files found in the project's build output and cache</li>
     * </ol>
     *
     * @param sourceRoots directories containing .java files
     * @param jarPaths    paths to JAR dependencies (can be empty)
     */
    public static CombinedTypeSolver create(List<Path> sourceRoots, List<Path> jarPaths) {
        var combined = new CombinedTypeSolver();

        // JDK types via reflection (includes java.lang, java.util, etc.)
        combined.add(new ReflectionTypeSolver());

        // Project source roots
        for (var root : sourceRoots) {
            if (Files.isDirectory(root)) {
                combined.add(new JavaParserTypeSolver(root));
            }
        }

        // JAR dependencies
        for (var jar : jarPaths) {
            try {
                combined.add(new JarTypeSolver(jar));
            } catch (IOException e) {
                System.err.println("Warning: could not load JAR: " + jar + " (" + e.getMessage() + ")");
            }
        }

        return combined;
    }

    /**
     * Discovers JAR files in common Gradle build locations for the given project.
     */
    public static List<Path> findGradleJars(Path projectDir) {
        // Look in Gradle's dependency cache and build/libs
        var searchDirs = List.of(
                projectDir.resolve("build/libs"),
                projectDir.resolve("build/dependencies")
        );

        // Also look for Gradle cache in user home
        var gradleCache = Path.of(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1");

        return searchDirs.stream()
                .filter(Files::isDirectory)
                .flatMap(TypeSolverFactory::findJarsIn)
                .toList();
    }

    /**
     * Resolves classpath JARs from a Gradle project by running
     * a Gradle task to print the runtime classpath.
     */
    public static List<Path> resolveGradleClasspath(Path projectDir) {
        try {
            var gradlew = projectDir.resolve("gradlew");
            var command = Files.isExecutable(gradlew)
                    ? gradlew.toString()
                    : "gradle";

            var process = new ProcessBuilder(
                    command,
                    "-q",
                    "dependencies",
                    "--configuration", "runtimeClasspath"
            )
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            // Alternative: use a custom init script to print the classpath
            var initScript = Files.createTempFile("classpath-", ".gradle");
            Files.writeString(initScript, """
                    allprojects {
                        task printClasspath {
                            doLast {
                                configurations.findByName('runtimeClasspath')?.files?.each {
                                    println "CP:" + it.absolutePath
                                }
                                configurations.findByName('compileClasspath')?.files?.each {
                                    println "CP:" + it.absolutePath
                                }
                            }
                        }
                    }
                    """);

            var cpProcess = new ProcessBuilder(
                    command,
                    "-q",
                    "--init-script", initScript.toString(),
                    "printClasspath"
            )
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            var output = new String(cpProcess.getInputStream().readAllBytes());
            cpProcess.waitFor();
            Files.deleteIfExists(initScript);

            return output.lines()
                    .filter(l -> l.startsWith("CP:"))
                    .map(l -> Path.of(l.substring(3)))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .distinct()
                    .toList();
        } catch (Exception e) {
            System.err.println("Warning: could not resolve Gradle classpath: " + e.getMessage());
            return List.of();
        }
    }

    private static Stream<Path> findJarsIn(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .toList()
                    .stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }
}
