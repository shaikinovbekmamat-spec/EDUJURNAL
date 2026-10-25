package com.example.edujurnal.dto;

import com.example.edujurnal.util.BellSchedule;

import com.example.edujurnal.entity.Lesson;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

@Getter @Setter
public class LessonForm {
    private Integer lessonNumber;
    private boolean repeatWeekly;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private java.time.LocalDate repeatUntil;
    private boolean applyFollowing;
    private String seriesRevision;
    private Long version;
    @NotNull(message = "Выберите класс.")
    private Long classId;
    private Long subgroupId;
    @NotNull(message = "Выберите предмет.")
    private Long subjectId;
    @NotNull(message = "Выберите учителя.")
    private Long teacherId;
    @NotNull(message = "Выберите кабинет.")
    private Long roomId;
    @NotNull(message = "Укажите дату.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private java.time.LocalDate date;
    @NotNull(message = "Укажите начало урока.")
    @DateTimeFormat(pattern = "HH:mm")
    private java.time.LocalTime startTime;
    @NotNull(message = "Укажите окончание урока.")
    @DateTimeFormat(pattern = "HH:mm")
    private java.time.LocalTime endTime;
    @NotNull(message = "Откройте форму заново.")
    @Pattern(regexp = "[a-f0-9\\-]{36}", message = "Откройте форму заново.")
    private String requestToken;

    public static LessonForm from(Lesson lesson) {
        LessonForm form = new LessonForm();
        form.setVersion(lesson.getVersion());
        form.setClassId(lesson.getClassId());
        form.setSubgroupId(lesson.getSubgroupId());
        form.setSubjectId(lesson.getSubjectId());
        form.setTeacherId(lesson.getTeacherId());
        form.setRoomId(lesson.getRoomId());
        form.setDate(lesson.getDate());
        form.setStartTime(lesson.getStartTime());
        form.setEndTime(lesson.getEndTime());
        form.setRequestToken(lesson.getRequestToken());
        form.setLessonNumber(BellSchedule.number(lesson));
        return form;
    }

    public LessonForm copy() {
        LessonForm copy = new LessonForm();
        copy.setVersion(version);
        copy.setClassId(classId);
        copy.setSubgroupId(subgroupId);
        copy.setSubjectId(subjectId);
        copy.setTeacherId(teacherId);
        copy.setRoomId(roomId);
        copy.setDate(date);
        copy.setStartTime(startTime);
        copy.setEndTime(endTime);
        copy.setRequestToken(requestToken);
        copy.setLessonNumber(lessonNumber);
        return copy;
    }
}