package com.github.sckwoky.typegraph.compose;

import com.github.sckwoky.typegraph.compose.model.ClassRole;

/**
 * Heuristic classification of classes by naming convention.
 * Used by {@link ChainRanker} for role-aware scoring (Stage 3).
 */
public class ClassRoleClassifier {

    public ClassRole classify(String typeFqn) {
        if (typeFqn == null) return ClassRole.UNKNOWN;
        String simple = simpleName(typeFqn);
        if (simple.endsWith("Repository") || simple.endsWith("Dao")) return ClassRole.REPOSITORY;
        if (simple.endsWith("Mapper")) return ClassRole.MAPPER;
        if (simple.endsWith("Service")) return ClassRole.SERVICE;
        if (simple.endsWith("Factory")) return ClassRole.FACTORY;
        if (simple.endsWith("Builder")) return ClassRole.BUILDER;
        if (simple.endsWith("Validator")) return ClassRole.VALIDATOR;
        if (simple.endsWith("Controller")) return ClassRole.CONTROLLER;
        if (simple.endsWith("Converter")) return ClassRole.CONVERTER;
        if (simple.endsWith("Util") || simple.endsWith("Utils") || simple.endsWith("Helper")) return ClassRole.UTIL;
        if (simple.endsWith("Dto") || simple.endsWith("DTO")) return ClassRole.DTO;
        if (simple.endsWith("Entity")) return ClassRole.ENTITY;
        return ClassRole.UNKNOWN;
    }

    private static String simpleName(String fqn) {
        int angle = fqn.indexOf('<');
        String base = angle < 0 ? fqn : fqn.substring(0, angle);
        int dot = base.lastIndexOf('.');
        return dot < 0 ? base : base.substring(dot + 1);
    }
}
