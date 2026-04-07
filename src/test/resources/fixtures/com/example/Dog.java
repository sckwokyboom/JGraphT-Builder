package com.example;

public class Dog implements Animal {
    private String name;
    private int age;
    private Owner owner;

    public Dog(String name, int age) {
        this.name = name;
        this.age = age;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int age() {
        return age;
    }

    public Owner getOwner() {
        return owner;
    }

    public void setOwner(Owner owner) {
        this.owner = owner;
    }
}
