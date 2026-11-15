package com.example.edujurnal.service;

import com.example.edujurnal.entity.Role;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StudentDashboardService {
    private final ScheduleAccessService access;
    private final JournalService journal;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public StudentDashboardService(ScheduleAccessService access, JournalService journal, JdbcTemplate jdbc, Clock clock) {
        this.access = access; this.journal = journal; this.jdbc = jdbc; this.clock = clock;
    }
    public record Lesson(String subject, String teacher, String room, LocalTime start, LocalTime end) {
        public String time() { return start.format(DateTimeFormatter.ofPattern("HH:mm")); }
        public String endTime() { return end.format(DateTimeFormatter.ofPattern("HH:mm")); }
    }
    public record Point(String label, String average, double height) {}
    public record View(String name, String login, String school, String className, String date, String period,
                       String average, long subjectCount, List<JournalService.Entry> grades,
                       List<Point> points, List<Lesson> lessons, Lesson next, String nextLabel) {}
    public View view(Authentication auth) {
        var user = access.currentUser(auth);
        if (user.getRole() != Role.STUDENT) throw new AccessDeniedException("Главная ученика доступна только ученику.");
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock);
        var year = journal.view(auth, false, null, null, null);
        var quarter = year.quarters().stream().filter(q -> !today.isBefore(q.start()) && !today.isAfter(q.end())).findFirst();
        var grades = year.entries().stream().filter(e -> e.grade() != null)
                .filter(e -> quarter.isEmpty() || (!e.date().isBefore(quarter.get().start()) && !e.date().isAfter(quarter.get().end())))
                .toList();
        String average = grades.isEmpty() ? "—" : decimal(grades.stream().mapToInt(JournalService.Entry::grade).average().orElseThrow());
        var byDay = grades.stream().collect(Collectors.groupingBy(JournalService.Entry::date, TreeMap::new,
                Collectors.averagingInt(JournalService.Entry::grade)));
        var points = byDay.entrySet().stream().skip(Math.max(0, byDay.size() - 7))
                .map(e -> new Point(e.getKey().format(DateTimeFormatter.ofPattern("dd.MM")), decimal(e.getValue()), e.getValue() * 20)).toList();
        String className = "Класс не назначен";
        List<Lesson> lessons = List.of();
        if (user.getStudentClassId() != null) {
            className = jdbc.queryForObject("select name from schedule_class where id=? and school_id=?", String.class,
                    user.getStudentClassId(), user.getSchool().getId()) + " класс";
            lessons = jdbc.query("""
                    select s.name as subject,t.name as teacher,r.name as room,l.start_time,l.end_time
                    from schedule_lesson l join schedule_subject s on s.id=l.subject_id
                    join schedule_teacher t on t.id=l.teacher_id join schedule_room r on r.id=l.room_id
                    where l.school_id=? and l.class_id=? and l.lesson_date=?
                    and (l.subgroup_id is null or exists(select 1 from schedule_membership m
                        where m.subgroup_id=l.subgroup_id and m.student_id=? and m.active_from<=l.lesson_date
                        and (m.active_to is null or m.active_to>=l.lesson_date)))
                    order by l.start_time,l.id
                    """, (r,n) -> new Lesson(r.getString("subject"), r.getString("teacher"), r.getString("room"),
                    r.getObject("start_time", LocalTime.class), r.getObject("end_time", LocalTime.class)),
                    user.getSchool().getId(), user.getStudentClassId(), today, user.getId());
        }
        Lesson next = lessons.stream().filter(l -> l.end().isAfter(now)).findFirst().orElse(null);
        String nextLabel = next == null ? "ВАШ УЧЕБНЫЙ ДЕНЬ" : next.start().isAfter(now)
                ? "СЛЕДУЮЩИЙ УРОК · ЧЕРЕЗ " + Math.max(1, Duration.between(now, next.start()).toMinutes()) + " МИН" : "СЕЙЧАС ИДЁТ УРОК";
        return new View(user.getFullName() == null || user.getFullName().isBlank() ? user.getLogin() : user.getFullName(),
                user.getLogin(), user.getSchool().getName(), className,
                today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru"))),
                quarter.map(JournalService.Period::name).orElse("Учебный год"), average,
                grades.stream().map(JournalService.Entry::subject).distinct().count(), grades.stream().limit(5).toList(),
                points, lessons, next, nextLabel);
    }
    private static String decimal(double value) { return String.format(Locale.forLanguageTag("ru"), "%.2f", value); }
}
