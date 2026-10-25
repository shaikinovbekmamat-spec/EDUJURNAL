package com.example.edujurnal.entity;

public enum Role {
    OWNER("Владелец"),
    DIRECTOR("Директор"),
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