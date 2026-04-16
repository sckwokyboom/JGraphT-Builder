package com.github.sckwoky.typegraph.flow.ts;

import com.github.sckwoky.typegraph.flow.spi.*;

import java.nio.file.Path;
import java.util.*;

/**
 * Optional {@link SourceIndexer} backed by jtreesitter.
 *
 * <p>This is a stub implementation with graceful fallback. The jtreesitter
 * native library is not bundled, so {@link #tryCreate()} catches all
 * {@link Throwable}s ({@link ClassNotFoundException},
 * {@link UnsatisfiedLinkError}, {@link NoClassDefFoundError}, etc.) and
 * returns {@link Optional#empty()}, causing the system to fall back to
 * {@link com.github.sckwoky.typegraph.flow.jdt.JdtSourceIndexer}.
 *
 * <p>Full implementation requires verifying the jtreesitter API and bundling
 * the platform-specific native library.
 */
public class TreeSitterSourceIndexer implements SourceIndexer {

    private TreeSitterSourceIndexer() {}

    /**
     * Attempts to create a {@link TreeSitterSourceIndexer}.
     *
     * <p>Returns {@link Optional#empty()} if the jtreesitter runtime is
     * unavailable (missing class or native library). Any {@link Throwable}
     * is caught so the caller always gets a usable result.
     *
     * @return an indexer backed by tree-sitter, or empty if unavailable
     */
    public static Optional<SourceIndexer> tryCreate() {
        try {
            Class.forName("io.github.treesitter.jtreesitter.Language");
            // If the class loads, try instantiation + smoke test
            return Optional.of(new TreeSitterSourceIndexer());
        } catch (Throwable t) {
            System.err.println("Tree-sitter unavailable (" + t.getClass().getSimpleName()
                    + "). Using JDT indexer.");
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stub — returns an empty index. In practice {@link #tryCreate()} will
     * return empty on most platforms since the jtreesitter native library is
     * not bundled. Full implementation requires jtreesitter API verification.
     */
    @Override
    public ProjectIndex indexProject(List<Path> sourceRoots) {
        // Stub: falls through to empty index if tree-sitter is available but
        // not fully implemented. In practice tryCreate() returns empty first.
        return new ProjectIndex(Map.of(), Map.of());
    }
}
