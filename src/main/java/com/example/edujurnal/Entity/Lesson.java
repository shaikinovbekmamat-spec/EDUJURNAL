package com.example.edujurnal.entity;

import com.example.edujurnal.dto.LessonForm;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "schedule_lesson")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Lesson {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private Long version;
    private Long schoolId;
    private Long classId;
    private Long subgroupId;
    private Long subjectId;
    private Long teacherId;
    private Long roomId;
    @Column(name = "lesson_date")
    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;
    private String requestToken;
    private String seriesId;

    public void assignSeries(String seriesId) {
        this.seriesId = seriesId;
    }

    public Lesson(long schoolId, LessonForm form) {
        this.schoolId = schoolId;
        this.requestToken = form.getRequestToken();
        apply(form);
    }

    public void apply(LessonForm form) {
        classId = form.getClassId();
        subgroupId = form.getSubgroupId();
        subjectId = form.getSubjectId();
        teacherId = form.getTeacherId();
        roomId = form.getRoomId();
        date = form.getDate();
        startTime = form.getStartTime();
        endTime = form.getEndTime();
    }
}