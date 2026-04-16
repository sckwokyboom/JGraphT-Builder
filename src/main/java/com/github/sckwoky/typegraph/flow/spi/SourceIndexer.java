package com.github.sckwoky.typegraph.flow.spi;
import java.nio.file.Path;
import java.util.List;
public interface SourceIndexer {
    ProjectIndex indexProject(List<Path> sourceRoots);
}
