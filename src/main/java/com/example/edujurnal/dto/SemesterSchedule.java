package com.example.edujurnal.dto;

import com.example.edujurnal.entity.Lesson;
import com.example.edujurnal.repository.ScheduleCatalog;
import com.example.edujurnal.util.AcademicTerm;
import com.example.edujurnal.util.BellSchedule;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** One timetable for the term; each recurring series is displayed only once. */
public record SemesterSchedule(List<WeekSchedule.Day> days, List<WeekSchedule.ClassWeek> classes,
                               List<Lesson> outsideGrid) {
    public static SemesterSchedule build(LocalDateTime now, AcademicTerm term, ScheduleCatalog.Catalog catalog,
                                         List<Lesson> lessons, Long selectedClass) {
        String[] names = {"Понедельник", "Вторник", "Среда", "Четверг", "Пятница"};
        LocalDate from = now.toLocalDate().isBefore(term.start()) ? term.start() : now.toLocalDate();
        var days = IntStream.range(0, 5).mapToObj(i -> new WeekSchedule.Day(
                from.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(i + 1))), names[i])).toList();
        var groups = lessons.stream().filter(l -> selectedClass == null || selectedClass.equals(l.getClassId()))
                .collect(Collectors.groupingBy(l -> l.getSeriesId() == null ? "lesson:" + l.getId() : l.getSeriesId()));
        Comparator<Lesson> order = Comparator.comparingInt((Lesson l) ->
                        l.getDate().atTime(l.getStartTime()).isAfter(now) ? 0 : 1)
                .thenComparingLong(l -> l.getDate().atTime(l.getStartTime()).isAfter(now)
                        ? l.getDate().toEpochDay() : -l.getDate().toEpochDay()).thenComparing(Lesson::getId);
        var visible = groups.values().stream().map(series -> series.stream().min(order).orElseThrow()).toList();
        var classes = catalog.classes().stream().filter(c -> selectedClass == null || selectedClass == c.id())
                .map(c -> {
                    var rows = BellSchedule.SLOTS.stream().map(slot -> new WeekSchedule.Row(slot,
                            days.stream().map(day -> {
                                LocalDate date = day.date();
                                if (!date.atTime(slot.start()).isAfter(now)) date = date.plusWeeks(1);
                                return new WeekSchedule.Cell(date, visible.stream()
                                        .filter(l -> l.getClassId() == c.id()
                                                && l.getDate().getDayOfWeek() == day.date().getDayOfWeek()
                                                && Integer.valueOf(slot.number()).equals(BellSchedule.number(l)))
                                        .sorted(Comparator.comparing(Lesson::getStartTime).thenComparing(Lesson::getId)).toList());
                            }).toList())).toList();
                    int count = rows.stream().flatMap(r -> r.cells().stream()).mapToInt(cell -> cell.lessons().size()).sum();
                    return new WeekSchedule.ClassWeek(c.id(), c.name(), count, rows);
                }).toList();
        var outside = visible.stream().filter(l -> BellSchedule.number(l) == null
                || l.getDate().getDayOfWeek().getValue() > 5).sorted(Comparator.comparing(Lesson::getDate)).toList();
        return new SemesterSchedule(days, classes, outside);
    }
}
