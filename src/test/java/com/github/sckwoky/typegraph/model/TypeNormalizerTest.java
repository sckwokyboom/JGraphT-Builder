package com.github.sckwoky.typegraph.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TypeNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "int, java.lang.Integer",
            "boolean, java.lang.Boolean",
            "double, java.lang.Double",
            "char, java.lang.Character",
            "byte, java.lang.Byte",
            "short, java.lang.Short",
            "long, java.lang.Long",
            "float, java.lang.Float",
    })
    void primitivesNormalizedToWrappers(String input, String expected) {
        assertThat(TypeNormalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    void voidReturnsNull() {
        assertThat(TypeNormalizer.normalize("void")).isNull();
    }

    @Test
    void nullAndBlankReturnNull() {
        assertThat(TypeNormalizer.normalize(null)).isNull();
        assertThat(TypeNormalizer.normalize("")).isNull();
        assertThat(TypeNormalizer.normalize("  ")).isNull();
    }

    @Test
    void regularTypesPassThrough() {
        assertThat(TypeNormalizer.normalize("com.example.Foo")).isEqualTo("com.example.Foo");
        assertThat(TypeNormalizer.normalize("java.lang.String")).isEqualTo("java.lang.String");
    }
}
