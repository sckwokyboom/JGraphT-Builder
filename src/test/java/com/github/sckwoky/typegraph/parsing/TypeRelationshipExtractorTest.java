package com.github.sckwoky.typegraph.parsing;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.sckwoky.typegraph.model.RelationshipKind;
import com.github.sckwoky.typegraph.parsing.TypeRelationshipExtractor.ExtractedRelationship;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TypeRelationshipExtractorTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");
    private static List<ExtractedRelationship> allRelationships;

    @BeforeAll
    static void parseFixtures() throws Exception {
        var typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(FIXTURES));

        var config = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        StaticJavaParser.setConfiguration(config);

        allRelationships = new ArrayList<>();

        for (var file : SourceScanner.findJavaFiles(FIXTURES)) {
            var cu = StaticJavaParser.parse(file);
            var extractor = new TypeRelationshipExtractor();
            extractor.visit(cu, null);
            allRelationships.addAll(extractor.getRelationships());
        }
    }

    @Test
    void extractsIsRelationship() {
        assertThat(allRelationships)
                .filteredOn(r -> r.kind() == RelationshipKind.IS)
                .anyMatch(r -> r.sourceType().equals("com.example.Dog")
                        && r.targetType().equals("com.example.Animal"));
    }

    @Test
    void extractsHasRelationship() {
        assertThat(allRelationships)
                .filteredOn(r -> r.kind() == RelationshipKind.HAS)
                .anyMatch(r -> r.sourceType().equals("com.example.Dog")
                        && r.targetType().equals("com.example.Owner"));
    }

    @Test
    void extractsProducesRelationship() {
        // Dog.getOwner() → Owner
        assertThat(allRelationships)
                .filteredOn(r -> r.kind() == RelationshipKind.PRODUCES
                        && r.signature() != null
                        && r.signature().methodName().equals("getOwner"))
                .anyMatch(r -> r.sourceType().equals("com.example.Dog")
                        && r.targetType().equals("com.example.Owner"));
    }

    @Test
    void extractsConsumesRelationship() {
        // Dog.setOwner(Owner) → Dog
        assertThat(allRelationships)
                .filteredOn(r -> r.kind() == RelationshipKind.CONSUMES
                        && r.signature() != null
                        && r.signature().methodName().equals("setOwner"))
                .anyMatch(r -> r.sourceType().equals("com.example.Owner")
                        && r.targetType().equals("com.example.Dog"));
    }

    @Test
    void constructorProducesSelf() {
        assertThat(allRelationships)
                .filteredOn(r -> r.kind() == RelationshipKind.PRODUCES
                        && r.signature() != null
                        && r.signature().methodName().equals("<init>")
                        && r.signature().declaringType().equals("com.example.Dog"))
                .anyMatch(r -> r.sourceType().equals("com.example.Dog")
                        && r.targetType().equals("com.example.Dog"));
    }

    @Test
    void primitivesNormalized() {
        // Dog(String, int) — int should be normalized to java.lang.Integer
        assertThat(allRelationships)
                .filteredOn(r -> r.kind() == RelationshipKind.CONSUMES
                        && r.signature() != null
                        && r.signature().methodName().equals("<init>")
                        && r.signature().declaringType().equals("com.example.Dog"))
                .anyMatch(r -> r.sourceType().equals("java.lang.Integer"));
    }
}
