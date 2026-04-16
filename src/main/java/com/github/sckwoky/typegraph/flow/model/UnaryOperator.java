package com.github.sckwoky.typegraph.flow.model;

import com.github.javaparser.ast.expr.UnaryExpr;

public enum UnaryOperator {
    NOT("!", true),
    NEG("-", true),
    BIT_NOT("~", true),
    PRE_INC("++", true),
    PRE_DEC("--", true),
    POST_INC("++", false),
    POST_DEC("--", false),
    PLUS("+", true);

    private final String symbol;
    private final boolean prefix;

    UnaryOperator(String symbol, boolean prefix) {
        this.symbol = symbol;
        this.prefix = prefix;
    }

    public String symbol() { return symbol; }
    public boolean prefix() { return prefix; }

    public static UnaryOperator fromJavaParser(UnaryExpr.Operator op) {
        return switch (op) {
            case LOGICAL_COMPLEMENT -> NOT;
            case MINUS -> NEG;
            case BITWISE_COMPLEMENT -> BIT_NOT;
            case PREFIX_INCREMENT -> PRE_INC;
            case PREFIX_DECREMENT -> PRE_DEC;
            case POSTFIX_INCREMENT -> POST_INC;
            case POSTFIX_DECREMENT -> POST_DEC;
            case PLUS -> PLUS;
        };
    }
}
