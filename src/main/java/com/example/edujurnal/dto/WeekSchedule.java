package com.example.edujurnal.dto;

import com.example.edujurnal.repository.ScheduleCatalog;
import com.example.edujurnal.util.BellSchedule;

import com.example.edujurnal.entity.Lesson;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

/** Builds exactly five day columns and six bell rows for each class. */
public record WeekSchedule(List<Day> days, List<ClassWeek> classes, List<Lesson> outsideGrid) {
    public record Day(LocalDate date, String name) {}
    public record Cell(LocalDate date, List<Lesson> lessons) {}
    public record Row(BellSchedule.Slot slot, List<Cell> cells) {}
    public record ClassWeek(long id, String name, int lessonCount, List<Row> rows) {}

    public static WeekSchedule build(LocalDate monday, ScheduleCatalog.Catalog catalog,
                                     List<Lesson> lessons, Long selectedClass) {
        String[] names = {"Понедельник", "Вторник", "Среда", "Четверг", "Пятница"};
        List<Day> days = IntStream.range(0, 5).mapToObj(i -> new Day(monday.plusDays(i), names[i])).toList();
        List<Lesson> visible = lessons.stream()
                .filter(l -> selectedClass == null || selectedClass.equals(l.getClassId())).toList();
        List<ClassWeek> classes = catalog.classes().stream()
                .filter(c -> selectedClass == null || selectedClass == c.id())
                .map(c -> {
                    List<Row> rows = BellSchedule.SLOTS.stream().map(slot -> new Row(slot, days.stream()
                            .map(day -> new Cell(day.date(), visible.stream()
                                    .filter(l -> l.getClassId() == c.id() && l.getDate().equals(day.date())
                                            && Integer.valueOf(slot.number()).equals(BellSchedule.number(l)))
                                    .toList())).toList())).toList();
                    int count = rows.stream().flatMap(r -> r.cells().stream()).mapToInt(cell -> cell.lessons().size()).sum();
                    return new ClassWeek(c.id(), c.name(), count, rows);
                }).toList();
        // Preserve visibility of records made before the fixed bell schedule existed.
        List<Lesson> outside = visible.stream().filter(l ->
                BellSchedule.number(l) == null || l.getDate().getDayOfWeek().getValue() > 5).toList();
        return new WeekSchedule(days, classes, outside);
    }
}
