package com.github.sckwoky.typegraph.flow.model;

import com.github.javaparser.ast.expr.AssignExpr;

public enum AssignOperator {
    ASSIGN("="),
    PLUS_ASSIGN("+="),
    MINUS_ASSIGN("-="),
    MULT_ASSIGN("*="),
    DIV_ASSIGN("/="),
    MOD_ASSIGN("%="),
    AND_ASSIGN("&="),
    OR_ASSIGN("|="),
    XOR_ASSIGN("^="),
    LSHIFT_ASSIGN("<<="),
    RSHIFT_ASSIGN(">>="),
    URSHIFT_ASSIGN(">>>=");

    private final String symbol;
    AssignOperator(String symbol) { this.symbol = symbol; }
    public String symbol() { return symbol; }

    public static AssignOperator fromJavaParser(AssignExpr.Operator op) {
        return switch (op) {
            case ASSIGN -> ASSIGN;
            case PLUS -> PLUS_ASSIGN;
            case MINUS -> MINUS_ASSIGN;
            case MULTIPLY -> MULT_ASSIGN;
            case DIVIDE -> DIV_ASSIGN;
            case REMAINDER -> MOD_ASSIGN;
            case BINARY_AND -> AND_ASSIGN;
            case BINARY_OR -> OR_ASSIGN;
            case XOR -> XOR_ASSIGN;
            case LEFT_SHIFT -> LSHIFT_ASSIGN;
            case SIGNED_RIGHT_SHIFT -> RSHIFT_ASSIGN;
            case UNSIGNED_RIGHT_SHIFT -> URSHIFT_ASSIGN;
        };
    }
}
