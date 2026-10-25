package com.example.edujurnal.exception;

import java.util.List;

public class ScheduleException extends RuntimeException {
    private final List<String> errors;

    public ScheduleException(String message) {
        this(List.of(message));
    }

    public ScheduleException(List<String> errors) {
        super(String.join(" ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
