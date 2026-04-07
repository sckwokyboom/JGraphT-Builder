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
    void genericsStripped() {
        assertThat(TypeNormalizer.normalize("java.util.List<java.lang.String>"))
                .isEqualTo("java.util.List");
    }

    @Test
    void arrayDimensionsStripped() {
        assertThat(TypeNormalizer.normalize("java.lang.String[]")).isEqualTo("java.lang.String");
        assertThat(TypeNormalizer.normalize("int[]")).isEqualTo("java.lang.Integer");
    }

    @Test
    void regularTypesPassThrough() {
        assertThat(TypeNormalizer.normalize("com.example.Foo")).isEqualTo("com.example.Foo");
    }
}
