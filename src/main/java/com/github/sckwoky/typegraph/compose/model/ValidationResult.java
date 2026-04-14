package com.github.sckwoky.typegraph.compose.model;

import java.util.List;

public sealed interface ValidationResult {
    boolean isValid();

    record Valid() implements ValidationResult {
        @Override public boolean isValid() { return true; }
    }

    record Invalid(List<String> reasons) implements ValidationResult {
        public Invalid {
            reasons = List.copyOf(reasons);
        }
        @Override public boolean isValid() { return false; }
    }
}
