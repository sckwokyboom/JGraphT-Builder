package com.github.sckwoky.typegraph.compose;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.sckwoky.typegraph.compose.model.AvailableField;
import com.github.sckwoky.typegraph.compose.model.TargetMethodSpec;
import com.github.sckwoky.typegraph.parsing.SourceScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses a textual signature like
 * <pre>com.example.OwnerService#getOwnerDto(java.lang.String, int)-&gt;com.example.OwnerDto</pre>
 * into a {@link TargetMethodSpec}, scanning the project source for the declaring
 * class to extract its fields with real names.
 */
public class TargetMethodSpecResolver {

    public TargetMethodSpec resolve(String signatureSpec, List<Path> sourceRoots) {
        var parsed = parseSignature(signatureSpec);
        var fields = extractFieldsForClass(parsed.declaringType(), sourceRoots);
        return new TargetMethodSpec(
                parsed.declaringType(),
                parsed.methodName(),
                parsed.paramTypes(),
                parsed.paramNames(),
                fields,
                parsed.returnType(),
                false  // isStatic — Stage 1 limitation, defaults to instance
        );
    }

    private record ParsedSignature(
            String declaringType, String methodName,
            List<String> paramTypes, List<String> paramNames,
            String returnType
    ) {}

    private ParsedSignature parseSignature(String spec) {
        int arrow = spec.indexOf("->");
        if (arrow < 0) throw new IllegalArgumentException("Missing '->' in signature: " + spec);
        String left = spec.substring(0, arrow).strip();
        String returnType = spec.substring(arrow + 2).strip();

        int hash = left.indexOf('#');
        if (hash < 0) throw new IllegalArgumentException("Missing '#' in signature: " + spec);
        String declaringType = left.substring(0, hash).strip();
        String rest = left.substring(hash + 1).strip();

        int openParen = rest.indexOf('(');
        int closeParen = rest.lastIndexOf(')');
        if (openParen < 0 || closeParen < 0 || closeParen < openParen) {
            throw new IllegalArgumentException("Invalid parameter list in: " + spec);
        }
        String methodName = rest.substring(0, openParen).strip();
        String paramsBlob = rest.substring(openParen + 1, closeParen).strip();

        List<String> paramTypes = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();
        if (!paramsBlob.isEmpty()) {
            int idx = 0;
            for (var raw : splitTopLevel(paramsBlob)) {
                String token = raw.strip();
                String[] parts = token.split("\\s+");
                if (parts.length >= 2) {
                    paramTypes.add(parts[0]);
                    paramNames.add(parts[1]);
                } else {
                    paramTypes.add(parts[0]);
                    paramNames.add("p" + idx);
                }
                idx++;
            }
        }
        return new ParsedSignature(declaringType, methodName, paramTypes, paramNames, returnType);
    }

    /** Splits a comma-separated parameter list, respecting nested generic angles. */
    private static List<String> splitTopLevel(String s) {
        var out = new ArrayList<String>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private List<AvailableField> extractFieldsForClass(String fqn, List<Path> sourceRoots) {
        var fields = new ArrayList<AvailableField>();
        for (var root : sourceRoots) {
            for (var file : SourceScanner.findJavaFiles(root)) {
                try {
                    var cu = StaticJavaParser.parse(file);
                    for (var decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                        if (decl.getFullyQualifiedName().map(n -> n.equals(fqn)).orElse(false)) {
                            for (FieldDeclaration fd : decl.getFields()) {
                                String typeStr = fd.getElementType().asString();
                                for (var v : fd.getVariables()) {
                                    fields.add(new AvailableField(v.getNameAsString(), typeStr, fqn));
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Silently skip files that fail to parse
                }
            }
        }
        return fields;
    }
}
