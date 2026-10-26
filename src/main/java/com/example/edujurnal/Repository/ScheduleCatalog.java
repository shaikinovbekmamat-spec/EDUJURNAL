package com.example.edujurnal.repository;

import com.example.edujurnal.exception.ScheduleException;
import com.example.edujurnal.dto.LessonForm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public class ScheduleCatalog {
    private final JdbcTemplate jdbc;

    public ScheduleCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Option(long id, String name, Long classId, Integer capacity, Integer studentCount) {}
    public record TeachingAssignment(long teacherId, long subjectId) {}

    public List<TeachingAssignment> teachingAssignments(long schoolId) {
        return jdbc.query("select teacher_id, subject_id from schedule_teacher_subject where school_id = ? order by teacher_id, subject_id",
                (rs, row) -> new TeachingAssignment(rs.getLong("teacher_id"), rs.getLong("subject_id")), schoolId);
    }

    public java.util.Map<Long, String> subjectTeachers(long schoolId) {
        return teachingAssignments(schoolId).stream().collect(java.util.stream.Collectors.groupingBy(
                TeachingAssignment::subjectId, java.util.stream.Collectors.mapping(a -> Long.toString(a.teacherId()),
                        java.util.stream.Collectors.joining(","))));
    }
    public record Catalog(List<Option> classes, List<Option> subgroups, List<Option> subjects,
                          List<Option> teachers, List<Option> rooms) {
        public String name(List<Option> options, Long id) {
            return options.stream().filter(o -> Long.valueOf(o.id()).equals(id))
                    .map(Option::name).findFirst().orElse("—");
        }
    }

    public Catalog forSchool(long schoolId) {
        return new Catalog(options("class", schoolId), options("subgroup", schoolId),
                options("subject", schoolId), options("teacher", schoolId), options("room", schoolId));
    }

    private List<Option> options(String type, long schoolId) {
        String parent = type.equals("subgroup") ? "class_id" : "cast(null as bigint)";
        String capacity = type.equals("room") ? "capacity" : "cast(null as integer)";
        String students = type.equals("class") ? "student_count" : "cast(null as integer)";
        return jdbc.query("select id, name, " + parent + " as parent_id, " + capacity + " as capacity, "
                        + students + " as student_count from schedule_" + type
                        + " where school_id = ? order by name, id",
                (rs, row) -> new Option(rs.getLong("id"), rs.getString("name"),
                        rs.getObject("parent_id", Long.class), rs.getObject("capacity", Integer.class),
                        rs.getObject("student_count", Integer.class)), schoolId).stream()
                .sorted(java.util.Comparator.comparingInt((Option o) -> type.equals("class")
                        ? Integer.parseInt(o.name().replaceAll("[^0-9]", "").isEmpty() ? "0" : o.name().replaceAll("[^0-9]", "")) : 0)
                        .thenComparing(Option::name)).toList();
    }

    public void lockSchool(long schoolId) {
        if (jdbc.queryForList("select id from schedule_school where id = ? for update", schoolId).isEmpty()) {
            throw new ScheduleException("Школа не найдена.");
        }
    }

    public boolean hasGrades(long lessonId) {
        return jdbc.queryForObject("select count(*) from journal_grade where lesson_id=?", Long.class, lessonId) > 0;
    }

    public void validate(long schoolId, LessonForm form) {
        Catalog c = forSchool(schoolId);
        require(c.classes(), form.getClassId(), "Класс");
        require(c.subjects(), form.getSubjectId(), "Предмет");
        require(c.teachers(), form.getTeacherId(), "Учитель");
        require(c.rooms(), form.getRoomId(), "Кабинет");
        if (jdbc.queryForObject("select count(*) from schedule_teacher_subject where school_id = ? and teacher_id = ? and subject_id = ?",
                Long.class, schoolId, form.getTeacherId(), form.getSubjectId()) == 0) {
            throw new ScheduleException("Преподаватель не назначен на выбранный предмет. Выберите преподавателя этого предмета.");
        }
        var schoolClass = c.classes().stream().filter(o -> Long.valueOf(o.id()).equals(form.getClassId())).findFirst().orElseThrow();
        var room = c.rooms().stream().filter(o -> Long.valueOf(o.id()).equals(form.getRoomId())).findFirst().orElseThrow();
        if (form.getSubgroupId() == null && schoolClass.studentCount() > room.capacity()) {
            throw new ScheduleException("Кабинет вмещает " + room.capacity() + " учеников, в классе — " + schoolClass.studentCount() + ".");
        }
        if (form.getSubgroupId() != null && c.subgroups().stream().noneMatch(o ->
                Long.valueOf(o.id()).equals(form.getSubgroupId()) && o.classId().equals(form.getClassId()))) {
            throw new ScheduleException("Подгруппа должна принадлежать выбранному классу.");
        }
    }

    private void require(List<Option> options, Long id, String label) {
        if (options.stream().noneMatch(o -> Long.valueOf(o.id()).equals(id))) {
            throw new ScheduleException(label + " не найден в этой школе.");
        }
    }

    public boolean shareStudents(long first, long second, LocalDate date) {
        return jdbc.queryForObject("""
                select count(*) from schedule_membership a
                join schedule_membership b on a.student_id = b.student_id
                where a.subgroup_id = ? and b.subgroup_id = ?
                and a.active_from <= ? and (a.active_to is null or a.active_to >= ?)
                and b.active_from <= ? and (b.active_to is null or b.active_to >= ?)
                """, Long.class, first, second, date, date, date, date) > 0;
    }
}
