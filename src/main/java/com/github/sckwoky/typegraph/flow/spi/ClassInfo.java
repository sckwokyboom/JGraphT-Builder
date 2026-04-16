package com.github.sckwoky.typegraph.flow.spi;
import java.nio.file.Path;
import java.util.List;
public record ClassInfo(
    String fqn, Path file, int startLine, int endLine,
    List<ExecutableInfo> executables, List<FieldInfo> fields
) {}
