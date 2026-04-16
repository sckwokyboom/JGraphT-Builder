package com.github.sckwoky.typegraph.flow.spi;
import java.nio.file.Path;
import java.util.*;
public record ProjectIndex(
    Map<String, ClassInfo> classesByFqn,
    Map<Path, List<ClassInfo>> classesByFile
) {
    public Optional<ClassInfo> findClass(String fqn) {
        return Optional.ofNullable(classesByFqn.get(fqn));
    }
    public List<ExecutableInfo> allExecutables() {
        return classesByFqn.values().stream()
            .flatMap(c -> c.executables().stream()).toList();
    }
}
