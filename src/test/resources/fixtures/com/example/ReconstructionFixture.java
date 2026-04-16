package com.example;

public class ReconstructionFixture {

    private int counter;

    public int simpleReturn(int x) {
        return x + 1;
    }

    public String ifElse(int x) {
        String result;
        if (x > 0) {
            result = "positive";
        } else {
            result = "non-positive";
        }
        return result;
    }

    public int whileLoop(int n) {
        int sum = 0;
        int i = 0;
        while (i < n) {
            sum = sum + i;
            i = i + 1;
        }
        return sum;
    }

    public int forLoop(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum = sum + i;
        }
        return sum;
    }

    public void methodCall(Owner owner) {
        owner.adoptDog("Rex", 3);
    }

    public String ternary(int x) {
        return x > 0 ? "yes" : "no";
    }

    public String nullCheck(Object obj) {
        if (obj == null) {
            return "null";
        }
        return obj.toString();
    }

    public void voidReturn() {
        return;
    }
}
