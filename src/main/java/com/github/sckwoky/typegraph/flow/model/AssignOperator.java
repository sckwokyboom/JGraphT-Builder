package com.github.sckwoky.typegraph.flow.model;

import com.github.javaparser.ast.expr.AssignExpr;
import org.eclipse.jdt.core.dom.Assignment;

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

    public static AssignOperator fromJdt(Assignment.Operator op) {
        if (op == Assignment.Operator.ASSIGN) return ASSIGN;
        if (op == Assignment.Operator.PLUS_ASSIGN) return PLUS_ASSIGN;
        if (op == Assignment.Operator.MINUS_ASSIGN) return MINUS_ASSIGN;
        if (op == Assignment.Operator.TIMES_ASSIGN) return MULT_ASSIGN;
        if (op == Assignment.Operator.DIVIDE_ASSIGN) return DIV_ASSIGN;
        if (op == Assignment.Operator.REMAINDER_ASSIGN) return MOD_ASSIGN;
        if (op == Assignment.Operator.BIT_AND_ASSIGN) return AND_ASSIGN;
        if (op == Assignment.Operator.BIT_OR_ASSIGN) return OR_ASSIGN;
        if (op == Assignment.Operator.BIT_XOR_ASSIGN) return XOR_ASSIGN;
        if (op == Assignment.Operator.LEFT_SHIFT_ASSIGN) return LSHIFT_ASSIGN;
        if (op == Assignment.Operator.RIGHT_SHIFT_SIGNED_ASSIGN) return RSHIFT_ASSIGN;
        if (op == Assignment.Operator.RIGHT_SHIFT_UNSIGNED_ASSIGN) return URSHIFT_ASSIGN;
        throw new IllegalArgumentException("Unknown JDT assignment operator: " + op);
    }
}
