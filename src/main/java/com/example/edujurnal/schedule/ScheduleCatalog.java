package com.example.edujurnal.schedule;

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

    public record Option(long id, String name, Long classId) {}
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
        String parent = type.equals("subgroup") ? "class_id" : "null";
        return jdbc.query("select id, name, " + parent + " as parent_id from schedule_" + type
                        + " where school_id = ? order by name, id",
                (rs, row) -> new Option(rs.getLong("id"), rs.getString("name"),
                        rs.getObject("parent_id", Long.class)), schoolId);
    }

    // СДЕЛАЛИ PUBLIC
    public void lockSchool(long schoolId) {
        if (jdbc.queryForList("select id from schedule_school where id = ? for update", schoolId).isEmpty()) {
            throw new ScheduleException("Школа не найдена.");
        }
    }

    // СДЕЛАЛИ PUBLIC
    public void validate(long schoolId, LessonForm form) {
        Catalog c = forSchool(schoolId);
        require(c.classes(), form.getClassId(), "Класс");
        require(c.subjects(), form.getSubjectId(), "Предмет");
        require(c.teachers(), form.getTeacherId(), "Учитель");
        require(c.rooms(), form.getRoomId(), "Кабинет");
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

    // СДЕЛАЛИ PUBLIC
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