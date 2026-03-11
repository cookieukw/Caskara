package com.cookie.caskara.entities;

public class User {
    private String name;
    private int age;
    // New fields added later will be null/0 by default
    private String email; 

    public User() {}

    public User(String name, int age) {
        this.name = name;
        this.age = age;
    }

    public String getName() { return name; }
    public int getAge() { return age; }
    public String getEmail() { return email; }

    @Override
    public String toString() {
        return "User{name='" + name + "', age=" + age + ", email='" + email + "'}";
    }
}
