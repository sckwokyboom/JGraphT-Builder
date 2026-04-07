package com.github.sckwoky.typegraph.parsing;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.sckwoky.typegraph.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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
 * <p>
 * Generic type information is extracted exclusively from the JavaParser AST:
 * <ul>
 *   <li>Primary path: {@link ResolvedType} API ({@code typeParametersValues()}, recursive)</li>
 *   <li>Fallback path: AST nodes ({@link ClassOrInterfaceType#getTypeArguments()})
 *       + import declarations for FQN resolution</li>
 * </ul>
 */
public class TypeRelationshipExtractor extends VoidVisitorAdapter<Void> {

    private final List<ExtractedRelationship> relationships = new ArrayList<>();

    /**
     * Import map: simple name → fully qualified name.
     * Built from the CompilationUnit's import declarations.
     */
    private final Map<String, String> importMap = new HashMap<>();

    /** Wildcard import packages, e.g. "com.github.javaparser.ast.body" from "import ...body.*". */
    private final List<String> wildcardImportPackages = new ArrayList<>();

    /** Package name of the current compilation unit. */
    private String currentPackage = "";

    public record ExtractedRelationship(
            String sourceType,
            String targetType,
            RelationshipKind kind,
            MethodSignature signature
    ) {}

    public List<ExtractedRelationship> getRelationships() {
        return List.copyOf(relationships);
    }

    // ---- CompilationUnit: build import map ----

    @Override
    public void visit(CompilationUnit cu, Void arg) {
        importMap.clear();
        wildcardImportPackages.clear();
        currentPackage = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isStatic()) {
                var importedName = imp.getNameAsString();
                if (imp.isAsterisk()) {
                    // "import com.foo.bar.*;" → package "com.foo.bar"
                    wildcardImportPackages.add(importedName);
                } else {
                    // "import com.foo.Bar;" → "Bar" → "com.foo.Bar"
                    int lastDot = importedName.lastIndexOf('.');
                    var simpleName = lastDot >= 0 ? importedName.substring(lastDot + 1) : importedName;
                    importMap.put(simpleName, importedName);
                }
            }
        }

        super.visit(cu, arg);
    }

    // ---- IS edges: extends / implements ----

    @Override
    public void visit(ClassOrInterfaceDeclaration decl, Void arg) {
        resolveDeclaringType(decl, (declaringFqn, ignored) -> {
            for (var extended : decl.getExtendedTypes()) {
                resolveType(extended, superFqn ->
                        relationships.add(new ExtractedRelationship(
                                declaringFqn, superFqn, RelationshipKind.IS, null)));
            }
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

            for (var param : decl.getParameters()) {
                resolveTypeFromNode(param.getType(), fieldFqn ->
                        relationships.add(new ExtractedRelationship(
                                declaringFqn, fieldFqn, RelationshipKind.HAS, null)));
            }

            var paramTypes = new ArrayList<String>();
            for (var param : decl.getParameters()) {
                var resolved = resolveAndNormalize(param.getType());
                paramTypes.add(resolved != null ? resolved : fallbackTypeName(param.getType()));
            }
            var sig = new MethodSignature(declaringFqn, "<init>", paramTypes, declaringFqn);

            for (var param : decl.getParameters()) {
                resolveTypeFromNode(param.getType(), paramFqn ->
                        relationships.add(new ExtractedRelationship(
                                paramFqn, declaringFqn, RelationshipKind.CONSUMES, sig)));
            }
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
                var resolved = resolveAndNormalize(param.getType());
                paramTypes.add(resolved != null ? resolved : fallbackTypeName(param.getType()));
            }

            var sig = new MethodSignature(
                    declaringFqn,
                    decl.getNameAsString(),
                    paramTypes,
                    returnTypeFqn != null ? returnTypeFqn : "void"
            );

            for (var param : decl.getParameters()) {
                resolveTypeFromNode(param.getType(), paramFqn ->
                        relationships.add(new ExtractedRelationship(
                                paramFqn, declaringFqn, RelationshipKind.CONSUMES, sig)));
            }

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
                var resolved = resolveAndNormalize(param.getType());
                paramTypes.add(resolved != null ? resolved : fallbackTypeName(param.getType()));
            }

            var sig = new MethodSignature(declaringFqn, "<init>", paramTypes, declaringFqn);

            for (var param : decl.getParameters()) {
                resolveTypeFromNode(param.getType(), paramFqn ->
                        relationships.add(new ExtractedRelationship(
                                paramFqn, declaringFqn, RelationshipKind.CONSUMES, sig)));
            }

            relationships.add(new ExtractedRelationship(
                    declaringFqn, declaringFqn, RelationshipKind.PRODUCES, sig));
        });
        super.visit(decl, arg);
    }

    // ========== Type resolution ==========

    private void resolveDeclaringType(TypeDeclaration<?> decl, BiConsumer<String, Void> action) {
        try {
            var resolved = decl.resolve();
            var fqn = resolved.getQualifiedName();
            if (fqn != null && !fqn.isBlank()) {
                action.accept(fqn, null);
            }
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            var name = decl.getFullyQualifiedName().orElse(decl.getNameAsString());
            if (name != null && !name.isBlank()) {
                action.accept(name, null);
            }
        }
    }

    private void findEnclosingType(BodyDeclaration<?> decl, Consumer<String> action) {
        var parent = decl.getParentNode();
        while (parent.isPresent()) {
            if (parent.get() instanceof TypeDeclaration<?> td) {
                resolveDeclaringType(td, (fqn, ignored) -> action.accept(fqn));
                return;
            }
            parent = parent.get().getParentNode();
        }
    }

    private void resolveType(ClassOrInterfaceType type, Consumer<String> action) {
        try {
            var resolved = type.resolve();
            var fqn = extractFqnFromResolved(resolved);
            if (fqn != null) {
                action.accept(fqn);
            }
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            var fqn = extractFqnFromAstType(type);
            if (fqn != null) {
                action.accept(fqn);
            }
        }
    }

    private void resolveTypeFromNode(Type type, Consumer<String> action) {
        try {
            var resolved = type.resolve();
            var fqn = extractFqnFromResolved(resolved);
            if (fqn != null) {
                action.accept(fqn);
            }
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            if (type.isClassOrInterfaceType()) {
                var fqn = extractFqnFromAstType(type.asClassOrInterfaceType());
                if (fqn != null) {
                    action.accept(fqn);
                }
            } else if (type.isPrimitiveType()) {
                var fqn = TypeNormalizer.normalize(type.asPrimitiveType().asString());
                if (fqn != null) {
                    action.accept(fqn);
                }
            }
            // Other types (void, array of unresolvable, etc.) — skip
        }
    }

    private String resolveAndNormalize(Type type) {
        try {
            var resolved = type.resolve();
            return extractFqnFromResolved(resolved);
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            if (type.isClassOrInterfaceType()) {
                return extractFqnFromAstType(type.asClassOrInterfaceType());
            }
            if (type.isPrimitiveType()) {
                return TypeNormalizer.normalize(type.asPrimitiveType().asString());
            }
            if (type.isVoidType()) {
                return null;
            }
            if (type.isArrayType()) {
                return resolveAndNormalize(type.asArrayType().getComponentType());
            }
            return null;
        }
    }

    // ========== FQN extraction from ResolvedType (primary path) ==========

    /**
     * Extracts FQN from a resolved type, including parameterized generics.
     * Generics are extracted via {@code ResolvedReferenceType.typeParametersValues()} —
     * the JavaParser resolved type AST, not string parsing.
     */
    private String extractFqnFromResolved(ResolvedType resolved) {
        if (resolved.isVoid()) {
            return null;
        }
        if (resolved.isPrimitive()) {
            return TypeNormalizer.normalize(resolved.asPrimitive().name().toLowerCase());
        }
        if (resolved.isArray()) {
            return extractFqnFromResolved(resolved.asArrayType().getComponentType());
        }
        if (resolved.isReferenceType()) {
            var refType = resolved.asReferenceType();
            var baseName = refType.getQualifiedName();
            var typeParams = refType.typeParametersValues();

            if (typeParams.isEmpty()) {
                return baseName;
            }
            if (typeParams.stream().allMatch(ResolvedType::isTypeVariable)) {
                return baseName;
            }

            var resolvedParamNames = typeParams.stream()
                    .map(this::extractFqnFromResolved)
                    .toList();

            if (resolvedParamNames.stream().anyMatch(p -> p == null)) {
                return baseName;
            }

            return baseName + "<" + String.join(", ", resolvedParamNames) + ">";
        }
        if (resolved.isTypeVariable()) {
            return null;
        }
        if (resolved.isWildcard()) {
            var wildcard = resolved.asWildcard();
            if (wildcard.isBounded()) {
                return extractFqnFromResolved(wildcard.getBoundedType());
            }
            return null;
        }
        return null;
    }

    // ========== FQN extraction from AST nodes (fallback path) ==========

    /**
     * Resolves a simple type name to its FQN using the import declarations
     * from the current CompilationUnit. This is AST-based: we walk the
     * {@link ImportDeclaration} nodes, not parse strings.
     *
     * @param simpleName e.g. "List", "Map", "ClassOrInterfaceType"
     * @return FQN if found in imports, or package-qualified name, or the simple name as-is
     */
    private String resolveSimpleNameViaImports(String simpleName) {
        // 1. Check explicit imports: "import com.foo.Bar;" → "Bar" maps to "com.foo.Bar"
        var fromImport = importMap.get(simpleName);
        if (fromImport != null) {
            return fromImport;
        }

        // 2. java.lang types are always implicitly imported
        var javaLangName = "java.lang." + simpleName;
        try {
            Class.forName(javaLangName);
            return javaLangName;
        } catch (ClassNotFoundException e) {
            // not a java.lang type
        }

        // 3. Wildcard imports: try each "import pkg.*" to see if pkg.SimpleName exists
        for (var pkg : wildcardImportPackages) {
            var candidate = pkg + "." + simpleName;
            try {
                Class.forName(candidate);
                return candidate;
            } catch (ClassNotFoundException e) {
                // not in this package — continue
            }
        }

        // 4. If class can't be found via reflection for wildcard imports,
        //    still prefer wildcard package over same-package (heuristic:
        //    if there's exactly one wildcard import, use it)
        if (wildcardImportPackages.size() == 1) {
            return wildcardImportPackages.getFirst() + "." + simpleName;
        }

        // 5. Same package: qualify with current package
        if (!currentPackage.isEmpty()) {
            return currentPackage + "." + simpleName;
        }

        return simpleName;
    }

    /**
     * Extracts a type name from a {@link ClassOrInterfaceType} AST node when symbol
     * resolution fails. Resolves simple names via import declarations,
     * and uses {@code getTypeArguments()} AST nodes for generics.
     */
    private String extractFqnFromAstType(ClassOrInterfaceType type) {
        // getNameWithScope() returns e.g. "Map.Entry" for inner types, "List" for simple
        var nameWithScope = type.getNameWithScope();
        if (nameWithScope == null || nameWithScope.isBlank()) {
            return null;
        }

        // Resolve the outermost name via imports
        // For "Map.Entry", resolve "Map" → "java.util.Map", result = "java.util.Map.Entry"
        var dotIndex = nameWithScope.indexOf('.');
        String resolvedBase;
        if (dotIndex > 0) {
            var outerName = nameWithScope.substring(0, dotIndex);
            var rest = nameWithScope.substring(dotIndex); // ".Entry"
            resolvedBase = resolveSimpleNameViaImports(outerName) + rest;
        } else {
            resolvedBase = resolveSimpleNameViaImports(nameWithScope);
        }

        // Check for type arguments via the AST node
        var typeArgs = type.getTypeArguments();
        if (typeArgs.isEmpty() || typeArgs.get().isEmpty()) {
            return resolvedBase;
        }

        // Recursively extract each type argument from its AST node
        var paramNames = typeArgs.get().stream()
                .map(this::extractFqnFromAstTypeArg)
                .toList();

        if (paramNames.stream().anyMatch(p -> p == null)) {
            return resolvedBase;
        }

        return resolvedBase + "<" + String.join(", ", paramNames) + ">";
    }

    /**
     * Extracts a type name from a type argument AST node.
     * Handles ClassOrInterfaceType, primitives, wildcards, arrays — all via AST.
     */
    private String extractFqnFromAstTypeArg(Type type) {
        if (type.isClassOrInterfaceType()) {
            return extractFqnFromAstType(type.asClassOrInterfaceType());
        }
        if (type.isPrimitiveType()) {
            return TypeNormalizer.normalize(type.asPrimitiveType().asString());
        }
        if (type.isWildcardType()) {
            var wt = type.asWildcardType();
            if (wt.getExtendedType().isPresent()) {
                return extractFqnFromAstTypeArg(wt.getExtendedType().get());
            }
            if (wt.getSuperType().isPresent()) {
                return extractFqnFromAstTypeArg(wt.getSuperType().get());
            }
            return null;
        }
        if (type.isArrayType()) {
            return extractFqnFromAstTypeArg(type.asArrayType().getComponentType());
        }
        return null;
    }

    /**
     * Best-effort raw type name for MethodSignature provenance when resolution fails.
     */
    private String fallbackTypeName(Type type) {
        if (type.isClassOrInterfaceType()) {
            var name = type.asClassOrInterfaceType().getNameWithScope();
            return resolveSimpleNameViaImports(name);
        }
        if (type.isPrimitiveType()) {
            return TypeNormalizer.normalize(type.asPrimitiveType().asString());
        }
        if (type.isVoidType()) {
            return "void";
        }
        return null;
    }
}
