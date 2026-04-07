package com.github.sckwoky.typegraph.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypeVertexTest {

    @Test
    void equalsByFqnOnly() {
        var a = new TypeVertex("com.example.Foo", TypeKind.CLASS);
        var b = new TypeVertex("com.example.Foo", TypeKind.INTERFACE);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void notEqualForDifferentFqn() {
        var a = new TypeVertex("com.example.Foo", TypeKind.CLASS);
        var b = new TypeVertex("com.example.Bar", TypeKind.CLASS);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void kindIsMutable() {
        var v = new TypeVertex("com.example.Foo", TypeKind.EXTERNAL);
        v.setKind(TypeKind.CLASS);
        assertThat(v.kind()).isEqualTo(TypeKind.CLASS);
    }

    @Test
    void shortNameSimple() {
        var v = new TypeVertex("com.example.Foo", TypeKind.CLASS);
        assertThat(v.shortName()).isEqualTo("Foo");
    }

    @Test
    void shortNameWithGenerics() {
        var v = new TypeVertex("java.util.List<java.lang.String>", TypeKind.EXTERNAL);
        assertThat(v.shortName()).isEqualTo("List<String>");
    }

    @Test
    void shortNameWithNestedGenerics() {
        var v = new TypeVertex("java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>", TypeKind.EXTERNAL);
        assertThat(v.shortName()).isEqualTo("Map<String, List<Integer>>");
    }

    @Test
    void shortNameWithMultipleTypeArgs() {
        var v = new TypeVertex("java.util.Map<java.lang.String, java.lang.Integer>", TypeKind.EXTERNAL);
        assertThat(v.shortName()).isEqualTo("Map<String, Integer>");
    }
}
