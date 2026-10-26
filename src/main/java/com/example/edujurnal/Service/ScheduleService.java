package com.example.edujurnal.service;

import com.example.edujurnal.exception.ScheduleException;
import com.example.edujurnal.repository.ScheduleCatalog;
import com.example.edujurnal.dto.LessonForm;
import com.example.edujurnal.util.BellSchedule;
import com.example.edujurnal.util.AcademicTerm;

import com.example.edujurnal.entity.Lesson;
import com.example.edujurnal.repository.LessonRepository;

import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

@Service
@Transactional(readOnly = true)
public class ScheduleService {
    private final LessonRepository lessons;
    private final ScheduleCatalog catalog;
    private final ScheduleValidator conflicts;
    private final Validator validator;
    private final Clock clock;

    public ScheduleService(LessonRepository lessons, ScheduleCatalog catalog, ScheduleValidator conflicts,
                           Validator validator, Clock clock) {
        this.lessons = lessons;
        this.catalog = catalog;
        this.conflicts = conflicts;
        this.validator = validator;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public List<Lesson> forTerm(long schoolId, AcademicTerm term) {
        return lessons.findBySchoolIdAndDateBetweenOrderByDateAscStartTimeAscIdAsc(schoolId, term.start(), term.end());
    }

    public List<Lesson> forTerm(long schoolId, AcademicTerm term, Long classId, Long teacherId) {
        return lessons.findVisibleForTerm(schoolId, term.start(), term.end(), classId, teacherId);
    }

    public boolean editable(Lesson lesson) {
        return lesson.getDate().atTime(lesson.getStartTime()).isAfter(LocalDateTime.now(clock)) && !catalog.hasGrades(lesson.getId());
    }

    public List<Lesson> forDate(long schoolId, LocalDate date) {
        return lessons.findBySchoolIdAndDateOrderByStartTimeAscIdAsc(schoolId, date);
    }

    public List<Lesson> forWeek(long schoolId, LocalDate monday) {
        return lessons.findBySchoolIdAndDateBetweenOrderByDateAscStartTimeAscIdAsc(
                schoolId, monday, monday.plusDays(6));
    }

    public boolean canAdd(LocalDate date, BellSchedule.Slot slot) {
        return date.atTime(slot.start()).isAfter(LocalDateTime.now(clock));
    }

    public LessonForm editForm(long schoolId, long id) {
        Lesson lesson = get(schoolId, id);
        LessonForm form = LessonForm.from(lesson);
        if (lesson.getSeriesId() != null) {
            form.setSeriesRevision(seriesRevision(series(schoolId, lesson.getSeriesId())));
        }
        return form;
    }

    public record SaveResult(Lesson first, int count) {}

    /**
     * The weekly editor accepts a bell number, never an arbitrary time.
     * All occurrences are checked and persisted in one transaction.
     */
    @Transactional
    public SaveResult savePlan(long schoolId, Long id, LessonForm form) {
        BellSchedule.Slot slot = BellSchedule.get(form.getLessonNumber());
        form.setStartTime(slot.start());
        form.setEndTime(slot.end());
        if (form.getDate() == null) throw new ScheduleException("Укажите дату первого урока.");
        if (form.getDate().getDayOfWeek().getValue() > 5) {
            throw new ScheduleException("Выберите будний день: с понедельника по пятницу.");
        }
        catalog.lockSchool(schoolId);
        if (id == null) {
            var previous = lessons.findByRequestTokenAndSchoolId(form.getRequestToken(), schoolId);
            if (previous.isPresent()) {
                Lesson lesson = previous.get();
                return new SaveResult(lesson, lesson.getSeriesId() == null ? 1 : series(schoolId, lesson.getSeriesId()).size());
            }
        }
        if (id != null && form.isApplyFollowing()) {
            Lesson selected = get(schoolId, id);
            if (selected.getSeriesId() == null) throw new ScheduleException("Этот урок не входит в еженедельное расписание.");
            List<Lesson> series = series(schoolId, selected.getSeriesId());
            if (!Objects.equals(form.getSeriesRevision(), seriesRevision(series))) {
                throw new ScheduleException("Еженедельное расписание уже изменено. Откройте форму заново.");
            }
            if (!Objects.equals(selected.getVersion(), form.getVersion())) {
                throw new ScheduleException("Урок уже изменён в другой вкладке. Откройте его заново.");
            }
            if (!form.getDate().equals(selected.getDate())) {
                throw new ScheduleException("Для переноса на другую дату выберите «Только этот урок».");
            }
            LocalDate termEnd = AcademicTerm.containing(selected.getDate()).end();
            List<Lesson> following = series.stream().filter(l -> !l.getDate().isBefore(selected.getDate())
                    && !l.getDate().isAfter(termEnd)).toList();
            for (Lesson occurrence : following) {
                LessonForm change = form.copy();
                change.setDate(occurrence.getDate());
                change.setVersion(occurrence.getVersion());
                saveWithDate(schoolId, occurrence.getId(), change);
            }
            return new SaveResult(selected, following.size());
        }
        if (id != null || !form.isRepeatWeekly()) {
            return new SaveResult(saveWithDate(schoolId, id, form), 1);
        }
        if (form.getRepeatUntil() == null || form.getRepeatUntil().isBefore(form.getDate())) {
            throw new ScheduleException("Укажите окончание периода не раньше первого урока.");
        }
        if (form.getRepeatUntil().isAfter(form.getDate().plusYears(1))) {
            throw new ScheduleException("Период повторения не должен превышать один год.");
        }
        String seriesId = UUID.randomUUID().toString();
        List<Lesson> saved = new ArrayList<>();
        for (LocalDate day = form.getDate(); !day.isAfter(form.getRepeatUntil()); day = day.plusWeeks(1)) {
            LessonForm occurrence = form.copy();
            occurrence.setDate(day);
            if (!day.equals(form.getDate())) {
                occurrence.setRequestToken(UUID.nameUUIDFromBytes(
                        (form.getRequestToken() + ":" + day).getBytes(StandardCharsets.UTF_8)).toString());
            }
            Lesson lesson = saveWithDate(schoolId, null, occurrence);
            lesson.assignSeries(seriesId);
            saved.add(lesson);
        }
        lessons.flush();
        return new SaveResult(saved.getFirst(), saved.size());
    }

    private Lesson saveWithDate(long schoolId, Long id, LessonForm form) {
        try {
            return save(schoolId, id, form);
        } catch (ScheduleException ex) {
            String date = form.getDate().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            throw new ScheduleException(ex.getErrors().stream().map(e -> date + ": " + e).toList());
        }
    }

    private List<Lesson> series(long schoolId, String seriesId) {
        return lessons.findBySchoolIdAndSeriesIdOrderByDateAscIdAsc(schoolId, seriesId);
    }

    private String seriesRevision(List<Lesson> series) {
        String versions = series.stream().map(l -> l.getId() + ":" + l.getVersion())
                .collect(java.util.stream.Collectors.joining(","));
        return UUID.nameUUIDFromBytes(versions.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public Lesson get(long schoolId, long id) {
        return lessons.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new ScheduleException("Урок не найден в этой школе."));
    }

    @Transactional
    public Lesson save(long schoolId, Long id, LessonForm form) {
        var errors = validator.validate(form);
        if (!errors.isEmpty()) {
            throw new ScheduleException(errors.stream().map(e -> e.getMessage()).sorted().toList());
        }
        // Serializes check + write across all application instances for this school.
        catalog.lockSchool(schoolId);
        if (id == null) {
            var previous = lessons.findByRequestTokenAndSchoolId(form.getRequestToken(), schoolId);
            if (previous.isPresent()) return previous.get();
        }
        Lesson lesson = id == null ? null : get(schoolId, id);
        if (lesson != null) {
            if (!Objects.equals(lesson.getVersion(), form.getVersion())) {
                throw new ScheduleException("Урок уже изменён в другой вкладке. Откройте его заново.");
            }
            if (!editable(lesson)) {
                throw new ScheduleException("Начавшийся или проведённый урок нельзя изменять.");
            }
        }
        if (!form.getStartTime().isBefore(form.getEndTime())) {
            throw new ScheduleException("Окончание урока должно быть позже начала.");
        }
        if (!form.getDate().atTime(form.getStartTime()).isAfter(LocalDateTime.now(clock))) {
            throw new ScheduleException("Выберите будущее время урока (время Кыргызстана).");
        }
        catalog.validate(schoolId, form);
        var found = conflicts.checkConflicts(form, id, forDate(schoolId, form.getDate()));
        if (!found.isEmpty()) throw new ScheduleException(found);
        if (lesson == null) lesson = new Lesson(schoolId, form);
        else lesson.apply(form);
        return lessons.saveAndFlush(lesson);
    }
}
