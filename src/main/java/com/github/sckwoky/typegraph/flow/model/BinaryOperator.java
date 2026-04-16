package com.github.sckwoky.typegraph.flow.model;

import com.github.javaparser.ast.expr.BinaryExpr;
import org.eclipse.jdt.core.dom.InfixExpression;

public enum BinaryOperator {
    PLUS("+", 11, true),
    MINUS("-", 11, true),
    MULT("*", 12, true),
    DIV("/", 12, true),
    MOD("%", 12, true),
    AND("&&", 4, true),
    OR("||", 3, true),
    EQ("==", 8, true),
    NE("!=", 8, true),
    LT("<", 9, true),
    GT(">", 9, true),
    LE("<=", 9, true),
    GE(">=", 9, true),
    BIT_AND("&", 7, true),
    BIT_OR("|", 5, true),
    BIT_XOR("^", 6, true),
    LSHIFT("<<", 10, true),
    RSHIFT(">>", 10, true),
    URSHIFT(">>>", 10, true);

    private final String symbol;
    private final int precedence;
    private final boolean leftAssociative;

    BinaryOperator(String symbol, int precedence, boolean leftAssociative) {
        this.symbol = symbol;
        this.precedence = precedence;
        this.leftAssociative = leftAssociative;
    }

    public String symbol() { return symbol; }
    public int precedence() { return precedence; }
    public boolean leftAssociative() { return leftAssociative; }

    public static BinaryOperator fromJavaParser(BinaryExpr.Operator op) {
        return switch (op) {
            case PLUS -> PLUS;
            case MINUS -> MINUS;
            case MULTIPLY -> MULT;
            case DIVIDE -> DIV;
            case REMAINDER -> MOD;
            case AND -> AND;
            case OR -> OR;
            case EQUALS -> EQ;
            case NOT_EQUALS -> NE;
            case LESS -> LT;
            case GREATER -> GT;
            case LESS_EQUALS -> LE;
            case GREATER_EQUALS -> GE;
            case BINARY_AND -> BIT_AND;
            case BINARY_OR -> BIT_OR;
            case XOR -> BIT_XOR;
            case LEFT_SHIFT -> LSHIFT;
            case SIGNED_RIGHT_SHIFT -> RSHIFT;
            case UNSIGNED_RIGHT_SHIFT -> URSHIFT;
        };
    }

    public static BinaryOperator fromJdt(InfixExpression.Operator op) {
        if (op == InfixExpression.Operator.PLUS) return PLUS;
        if (op == InfixExpression.Operator.MINUS) return MINUS;
        if (op == InfixExpression.Operator.TIMES) return MULT;
        if (op == InfixExpression.Operator.DIVIDE) return DIV;
        if (op == InfixExpression.Operator.REMAINDER) return MOD;
        if (op == InfixExpression.Operator.CONDITIONAL_AND) return AND;
        if (op == InfixExpression.Operator.CONDITIONAL_OR) return OR;
        if (op == InfixExpression.Operator.EQUALS) return EQ;
        if (op == InfixExpression.Operator.NOT_EQUALS) return NE;
        if (op == InfixExpression.Operator.LESS) return LT;
        if (op == InfixExpression.Operator.GREATER) return GT;
        if (op == InfixExpression.Operator.LESS_EQUALS) return LE;
        if (op == InfixExpression.Operator.GREATER_EQUALS) return GE;
        if (op == InfixExpression.Operator.AND) return BIT_AND;
        if (op == InfixExpression.Operator.OR) return BIT_OR;
        if (op == InfixExpression.Operator.XOR) return BIT_XOR;
        if (op == InfixExpression.Operator.LEFT_SHIFT) return LSHIFT;
        if (op == InfixExpression.Operator.RIGHT_SHIFT_SIGNED) return RSHIFT;
        if (op == InfixExpression.Operator.RIGHT_SHIFT_UNSIGNED) return URSHIFT;
        throw new IllegalArgumentException("Unknown JDT infix operator: " + op);
    }
}
