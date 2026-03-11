package com.cookie.caskara.entities;

import java.util.ArrayList;
import java.util.List;


public class FruitBasket {
    private String basketName;
    private List<Fruit> fruits;

    public FruitBasket() {
        this.fruits = new java.util.ArrayList<>();
    }

    public FruitBasket(String basketName) {
        this.basketName = basketName;
        this.fruits = new ArrayList<>();
    }

    public static class Fruit {
        public String name;
        public String color;
        public double weight;

        public Fruit(String name, String color, double weight) {
            this.name = name;
            this.color = color;
            this.weight = weight;
        }

        @Override
        public String toString() {
            return name + " (" + color + ")";
        }
    }

    public void addFruit(String name, String color, double weight) {
        this.fruits.add(new Fruit(name, color, weight));
    }

    public List<Fruit> getFruits() {
        return fruits;
    }

    @Override
    public String toString() {
        return "FruitBasket{" +
                "name='" + basketName + '\'' +
                ", fruits=" + fruits +
                '}';
    }
}
