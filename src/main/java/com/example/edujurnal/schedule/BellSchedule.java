package com.example.edujurnal.schedule;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public final class BellSchedule {
    public record Slot(int number, LocalTime start, LocalTime end, int breakMinutes) {}

    public static final List<Slot> SLOTS;
    static {
        List<Slot> slots = new ArrayList<>();
        LocalTime start = LocalTime.of(8, 0);
        for (int number = 1; number <= 6; number++) {
            LocalTime end = start.plusMinutes(45);
            int pause = number == 3 ? 15 : number == 6 ? 0 : 5;
            slots.add(new Slot(number, start, end, pause));
            start = end.plusMinutes(pause);
        }
        SLOTS = List.copyOf(slots);
    }

    private BellSchedule() {}

    public static Slot get(Integer number) {
        if (number == null || number < 1 || number > 6) {
            throw new ScheduleException("Выберите номер урока от 1 до 6.");
        }
        return SLOTS.get(number - 1);
    }

    public static Integer number(Lesson lesson) {
        return SLOTS.stream().filter(s -> s.start().equals(lesson.getStartTime())
                && s.end().equals(lesson.getEndTime())).map(Slot::number).findFirst().orElse(null);
    }
}
