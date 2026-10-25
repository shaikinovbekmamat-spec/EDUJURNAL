package com.example.edujurnal.service;

import com.example.edujurnal.repository.ScheduleCatalog;
import com.example.edujurnal.dto.LessonForm;

import com.example.edujurnal.entity.Lesson;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class ScheduleValidator {
    private final ScheduleCatalog catalog;

    public ScheduleValidator(ScheduleCatalog catalog) {
        this.catalog = catalog;
    }

    public List<String> checkConflicts(LessonForm proposed, Long excludedId, List<Lesson> lessons) {
        List<String> conflicts = new ArrayList<>();
        for (Lesson other : lessons) {
            if (Objects.equals(other.getId(), excludedId) || !other.getDate().equals(proposed.getDate())
                    || !proposed.getStartTime().isBefore(other.getEndTime())
                    || !other.getStartTime().isBefore(proposed.getEndTime())) {
                continue;
            }
            String detail = " (урок №" + other.getId() + ", " + other.getStartTime()
                    + "–" + other.getEndTime() + ").";
            if (other.getTeacherId().equals(proposed.getTeacherId())) {
                conflicts.add("Учитель уже занят" + detail);
            }
            if (other.getRoomId().equals(proposed.getRoomId())) {
                conflicts.add("Кабинет уже занят" + detail);
            }
            if (sameStudents(proposed, other)) {
                conflicts.add("У класса или учеников подгруппы уже есть урок" + detail);
            }
        }
        return conflicts;
    }

    private boolean sameStudents(LessonForm form, Lesson other) {
        if (form.getClassId().equals(other.getClassId()) &&
                (form.getSubgroupId() == null || other.getSubgroupId() == null
                        || form.getSubgroupId().equals(other.getSubgroupId()))) {
            return true;
        }
        return form.getSubgroupId() != null && other.getSubgroupId() != null
                && catalog.shareStudents(form.getSubgroupId(), other.getSubgroupId(), form.getDate());
    }
}
