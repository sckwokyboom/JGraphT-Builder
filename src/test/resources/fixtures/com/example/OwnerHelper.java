package com.example;

/**
 * Test fixture exercising every control structure the flow graph should
 * recognise: try/catch/finally, if/else, for-each, while, switch, ternary,
 * field reads, local definitions, chained calls, multiple returns.
 */
public class OwnerHelper {

    private final Owner owner;
    private int retries;

    public OwnerHelper(Owner owner) {
        this.owner = owner;
        this.retries = 3;
    }

    public Dog findOrAdopt(String name, int age) {
        Dog result;
        try {
            result = lookup(name);
        } catch (RuntimeException e) {
            result = null;
        } finally {
            retries = retries - 1;
        }

        if (result == null) {
            result = owner.adoptDog(name, age);
        } else if (age > 0) {
            result.setOwner(owner);
        }

        for (Dog d : owner.getDogs()) {
            if (d.name().equals(name)) {
                return d;
            }
        }

        int counter = 0;
        while (counter < retries) {
            result.setOwner(owner);
            counter = counter + 1;
        }

        return result;
    }

    private Dog lookup(String name) {
        for (Dog d : owner.getDogs()) {
            if (d.name().equals(name)) {
                return d;
            }
        }
        throw new RuntimeException("not found");
    }

    public String describe(Dog dog) {
        return dog == null ? "none" : dog.name();
    }
}
