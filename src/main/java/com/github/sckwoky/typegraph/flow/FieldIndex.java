package com.github.sckwoky.typegraph.flow;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Index of fields declared on the enclosing class of the method being analyzed.
 * Used by the expression analyzer to distinguish unqualified name references
 * to fields (FIELD_READ with FieldOrigin.THIS) from local variables.
 */
public class FieldIndex {

    private final Map<String, String> nameToType;

    public FieldIndex(TypeDeclaration<?> declaringType) {
        this.nameToType = new HashMap<>();
        if (declaringType == null) return;
        for (FieldDeclaration fd : declaringType.getFields()) {
            String type = fd.getElementType().asString();
            for (var v : fd.getVariables()) {
                nameToType.put(v.getNameAsString(), type);
            }
        }
    }

    public FieldIndex(List<com.github.sckwoky.typegraph.flow.spi.FieldInfo> fields) {
        this.nameToType = new LinkedHashMap<>();
        for (var f : fields) {
            nameToType.put(f.name(), f.type());
        }
    }

    public boolean contains(String name) { return nameToType.containsKey(name); }

    public String typeOf(String name) { return nameToType.get(name); }
}
