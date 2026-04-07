package com.example;

import java.util.List;

public class Owner {
    private String name;
    private List<Dog> dogs;

    public Owner(String name) {
        this.name = name;
    }

    public Dog adoptDog(String dogName, int age) {
        var dog = new Dog(dogName, age);
        dog.setOwner(this);
        dogs.add(dog);
        return dog;
    }

    public List<Dog> getDogs() {
        return dogs;
    }
}
