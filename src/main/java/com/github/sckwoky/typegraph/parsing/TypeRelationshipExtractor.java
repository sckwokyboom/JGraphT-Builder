package com.github.sckwoky.typegraph.parsing;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.sckwoky.typegraph.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * AST visitor that extracts type relationships from Java source files.
 * <p>
 * Produces four kinds of relationships:
 * <ul>
 *   <li>IS: subtype → supertype (extends/implements)</li>
 *   <li>HAS: declaring type → field type</li>
 *   <li>CONSUMES: parameter type → declaring type</li>
 *   <li>PRODUCES: declaring type → return type</li>
 * </ul>
 */
public class TypeRelationshipExtractor extends VoidVisitorAdapter<Void> {

    /** Extracted relationships so far. */
    private final List<ExtractedRelationship> relationships = new ArrayList<>();

    public record ExtractedRelationship(
            String sourceType,
            String targetType,
            RelationshipKind kind,
            MethodSignature signature
    ) {}

    public List<ExtractedRelationship> getRelationships() {
        return List.copyOf(relationships);
    }

    // ---- IS edges: extends / implements ----

    @Override
    public void visit(ClassOrInterfaceDeclaration decl, Void arg) {
        resolveDeclaringType(decl, (declaringFqn, ignored) -> {
            // extends
            for (var extended : decl.getExtendedTypes()) {
                resolveType(extended, superFqn ->
                        relationships.add(new ExtractedRelationship(
                                declaringFqn, superFqn, RelationshipKind.IS, null)));
            }
            // implements
            for (var implemented : decl.getImplementedTypes()) {
                resolveType(implemented, superFqn ->
                        relationships.add(new ExtractedRelationship(
                                declaringFqn, superFqn, RelationshipKind.IS, null)));
            }
        });

        super.visit(decl, arg);
    }

    @Override
    public void visit(EnumDeclaration decl, Void arg) {
        resolveDeclaringType(decl, (declaringFqn, ignored) -> {
            for (var implemented : decl.getImplementedTypes()) {
                resolveType(implemented, superFqn ->
                        relationships.add(new ExtractedRelationship(
                                declaringFqn, superFqn, RelationshipKind.IS, null)));
            }
        });
        super.visit(decl, arg);
    }

    @Override
    public void visit(RecordDeclaration decl, Void arg) {
        resolveDeclaringType(decl, (declaringFqn, ignored) -> {
            for (var implemented : decl.getImplementedTypes()) {
                resolveType(implemented, superFqn ->
                        relationships.add(new ExtractedRelationship(
                                declaringFqn, superFqn, RelationshipKind.IS, null)));
            }

            // Record components → HAS edges
            for (var param : decl.getParameters()) {
                resolveTypeFromNode(param.getType(), fieldFqn ->
                        relationships.add(new ExtractedRelationship(
                                declaringFqn, fieldFqn, RelationshipKind.HAS, null)));
            }

            // Record components → CONSUMES edges for canonical constructor
            var paramTypes = new ArrayList<String>();
            for (var param : decl.getParameters()) {
                var normalized = resolveAndNormalize(param.getType());
                paramTypes.add(normalized != null ? normalized : param.getType().asString());
            }
            var sig = new MethodSignature(declaringFqn, "<init>", paramTypes, declaringFqn);

            for (var param : decl.getParameters()) {
                resolveTypeFromNode(param.getType(), paramFqn ->
                        relationships.add(new ExtractedRelationship(
                                paramFqn, declaringFqn, RelationshipKind.CONSUMES, sig)));
            }
            // Canonical constructor PRODUCES self
            relationships.add(new ExtractedRelationship(
                    declaringFqn, declaringFqn, RelationshipKind.PRODUCES, sig));
        });

        super.visit(decl, arg);
    }

    // ---- HAS edges: fields ----

    @Override
    public void visit(FieldDeclaration decl, Void arg) {
        findEnclosingType(decl, declaringFqn ->
                resolveTypeFromNode(decl.getCommonType(), fieldTypeFqn ->
                        relationships.add(new ExtractedRelationship(
                                declaringFqn, fieldTypeFqn, RelationshipKind.HAS, null))));

        super.visit(decl, arg);
    }

    // ---- CONSUMES + PRODUCES edges: methods ----

    @Override
    public void visit(MethodDeclaration decl, Void arg) {
        findEnclosingType(decl, declaringFqn -> {
            var returnTypeFqn = resolveAndNormalize(decl.getType());
            var paramTypes = new ArrayList<String>();
            for (var param : decl.getParameters()) {
                var normalized = resolveAndNormalize(param.getType());
                paramTypes.add(normalized != null ? normalized : param.getType().asString());
            }

            var sig = new MethodSignature(
                    declaringFqn,
                    decl.getNameAsString(),
                    paramTypes,
                    returnTypeFqn != null ? returnTypeFqn : "void"
            );

            // CONSUMES: paramType → declaringType
            for (var param : decl.getParameters()) {
                resolveTypeFromNode(param.getType(), paramFqn ->
                        relationships.add(new ExtractedRelationship(
                                paramFqn, declaringFqn, RelationshipKind.CONSUMES, sig)));
            }

            // PRODUCES: declaringType → returnType
            if (returnTypeFqn != null) {
                relationships.add(new ExtractedRelationship(
                        declaringFqn, returnTypeFqn, RelationshipKind.PRODUCES, sig));
            }
        });

        super.visit(decl, arg);
    }

    // ---- CONSUMES + PRODUCES edges: constructors ----

    @Override
    public void visit(ConstructorDeclaration decl, Void arg) {
        findEnclosingType(decl, declaringFqn -> {
            var paramTypes = new ArrayList<String>();
            for (var param : decl.getParameters()) {
                var normalized = resolveAndNormalize(param.getType());
                paramTypes.add(normalized != null ? normalized : param.getType().asString());
            }

            var sig = new MethodSignature(declaringFqn, "<init>", paramTypes, declaringFqn);

            // CONSUMES: paramType → declaringType
            for (var param : decl.getParameters()) {
                resolveTypeFromNode(param.getType(), paramFqn ->
                        relationships.add(new ExtractedRelationship(
                                paramFqn, declaringFqn, RelationshipKind.CONSUMES, sig)));
            }

            // PRODUCES: declaringType → self
            relationships.add(new ExtractedRelationship(
                    declaringFqn, declaringFqn, RelationshipKind.PRODUCES, sig));
        });

        super.visit(decl, arg);
    }

    // ---- Helpers ----

    private void resolveDeclaringType(TypeDeclaration<?> decl, BiConsumer<String, Void> action) {
        try {
            var resolved = decl.resolve();
            var fqn = TypeNormalizer.normalize(resolved.getQualifiedName());
            if (fqn != null) {
                action.accept(fqn, null);
            }
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            // Fallback: use the name as-is
            var name = decl.getFullyQualifiedName().orElse(decl.getNameAsString());
            var fqn = TypeNormalizer.normalize(name);
            if (fqn != null) {
                action.accept(fqn, null);
            }
        }
    }

    private void findEnclosingType(BodyDeclaration<?> decl, java.util.function.Consumer<String> action) {
        var parent = decl.getParentNode();
        while (parent.isPresent()) {
            if (parent.get() instanceof TypeDeclaration<?> td) {
                resolveDeclaringType(td, (fqn, ignored) -> action.accept(fqn));
                return;
            }
            parent = parent.get().getParentNode();
        }
    }

    private void resolveType(ClassOrInterfaceType type, java.util.function.Consumer<String> action) {
        try {
            var resolved = type.resolve();
            var fqn = extractFqn(resolved);
            if (fqn != null) {
                action.accept(fqn);
            }
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            var fqn = TypeNormalizer.normalize(type.getNameWithScope());
            if (fqn != null) {
                action.accept(fqn);
            }
        }
    }

    private void resolveTypeFromNode(com.github.javaparser.ast.type.Type type,
                                     java.util.function.Consumer<String> action) {
        try {
            var resolved = type.resolve();
            var fqn = extractFqn(resolved);
            if (fqn != null) {
                action.accept(fqn);
            }
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            var fqn = TypeNormalizer.normalize(type.asString());
            if (fqn != null) {
                action.accept(fqn);
            }
        }
    }

    private String resolveAndNormalize(com.github.javaparser.ast.type.Type type) {
        try {
            var resolved = type.resolve();
            return extractFqn(resolved);
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            return TypeNormalizer.normalize(type.asString());
        }
    }

    private String extractFqn(ResolvedType resolved) {
        if (resolved.isVoid()) return null;
        if (resolved.isPrimitive()) {
            return TypeNormalizer.normalize(resolved.asPrimitive().name().toLowerCase());
        }
        if (resolved.isArray()) {
            return extractFqn(resolved.asArrayType().getComponentType());
        }
        if (resolved.isReferenceType()) {
            return TypeNormalizer.normalize(resolved.asReferenceType().getQualifiedName());
        }
        if (resolved.isTypeVariable()) {
            // Generic type variable like T — skip
            return null;
        }
        if (resolved.isWildcard()) {
            return null;
        }
        return null;
    }
}
