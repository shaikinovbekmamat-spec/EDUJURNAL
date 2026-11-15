package com.example.edujurnal.util;

import java.time.LocalDate;

public record AcademicTerm(LocalDate start, LocalDate end, String name) {
    public AcademicTerm previous() {
        return containing(start.getMonthValue() == 1 ? start.minusMonths(4) : start.minusMonths(5));
    }

    public AcademicTerm next() {
        return containing(end.plusMonths(2));
    }

    public static AcademicTerm containing(LocalDate date) {
        int year = date.getYear();
        if (date.getMonthValue() <= 5) {
            return new AcademicTerm(LocalDate.of(year, 1, 1), LocalDate.of(year, 5, 31), "II полугодие");
        }
        return new AcademicTerm(LocalDate.of(year, 9, 1), LocalDate.of(year, 12, 31), "I полугодие");
    }
}
