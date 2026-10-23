package com.example.edujurnal.Entity;

public enum Role {
    OWNER("Владелец"),
    DIRECTOR("Директор"), // вместо ADMIN
    TEACHER("Учитель"),
    STUDENT("Ученик"),
    PARENT("Родитель");

    private final String displayName;

    Role(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}