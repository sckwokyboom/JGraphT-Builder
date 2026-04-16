package com.github.sckwoky.typegraph.flow.spi;
import java.nio.file.Path;
import java.util.List;
public record ExecutableInfo(
    ExecutableKind kind, String name, String declaringType,
    List<ParamInfo> parameters, String returnType,
    Path file, int startLine, int endLine
) {}
