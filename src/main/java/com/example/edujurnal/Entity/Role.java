package com.example.edujurnal.Entity;

public enum Role {
    ADMIN("Администратор"),
    TEACHER("Учитель"),
    STUDENT("Ученик");

    private final String displayName;

    Role(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
