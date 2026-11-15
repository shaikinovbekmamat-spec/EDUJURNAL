package com.example.edujurnal.service;

import com.example.edujurnal.dto.GenerationForm;
import com.example.edujurnal.exception.ScheduleException;
import com.example.edujurnal.repository.ScheduleCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class CurriculumService {
    private final JdbcTemplate jdbc;
    private final ScheduleCatalog catalog;

    public CurriculumService(JdbcTemplate jdbc, ScheduleCatalog catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public record ClassSummary(long id, String name, int hours, int subjects) {}
    public record TeacherSummary(long id, String name, int hours) {}

    public Map<Long, Integer> classLimits(long school) {
        Map<Long, Integer> limits = new TreeMap<>();
        jdbc.query("select class_id,weekly_limit from schedule_class_weekly_limit where school_id=?",
                rs -> { limits.put(rs.getLong(1), rs.getInt(2)); }, school);
        return limits;
    }

    public Map<Long, Integer> teacherLimits(long school) {
        Map<Long, Integer> limits = new TreeMap<>();
        jdbc.query("select teacher_id,weekly_limit from schedule_teacher_weekly_limit where school_id=?",
                rs -> { limits.put(rs.getLong(1), rs.getInt(2)); }, school);
        return limits;
    }

    @Transactional
    public void saveWeeklyLimit(long school, String type, long id, int limit) {
        catalog.lockSchool(school);
        if (limit < 0 || limit > 30) throw new ScheduleException("Лимит должен быть от 0 до 30 уроков в неделю.");
        if ("class".equals(type)) {
            requireClass(school, id);
            jdbc.update("delete from schedule_class_weekly_limit where school_id=? and class_id=?", school, id);
            jdbc.update("insert into schedule_class_weekly_limit values(?,?,?)", school, id, limit);
        } else if ("teacher".equals(type)) {
            if (catalog.forSchool(school).teachers().stream().noneMatch(t -> t.id() == id))
                throw new ScheduleException("Учитель не найден в этой школе.");
            jdbc.update("delete from schedule_teacher_weekly_limit where school_id=? and teacher_id=?", school, id);
            jdbc.update("insert into schedule_teacher_weekly_limit values(?,?,?)", school, id, limit);
        } else throw new ScheduleException("Выберите класс или учителя.");
    }

    public List<TeacherSummary> teacherSummary(long school) {
        var rows = loads(school);
        return catalog.forSchool(school).teachers().stream().map(t -> new TeacherSummary(t.id(), t.name(),
                rows.stream().filter(l -> Objects.equals(l.getTeacherId(), t.id()))
                        .mapToInt(GenerationForm.Load::getWeeklyCount).sum())).toList();
    }

    public Map<Long, List<Integer>> workingDays(long school) {
        var blocked = unavailable(school);
        Map<Long, List<Integer>> result = new LinkedHashMap<>();
        for (var teacher : catalog.forSchool(school).teachers()) {
            result.put(teacher.id(), java.util.stream.IntStream.rangeClosed(1, 5)
                    .filter(day -> blocked.stream().noneMatch(u -> u.teacherId() == teacher.id() && u.weekday() == day))
                    .boxed().toList());
        }
        return result;
    }

    @Transactional
    public void saveWorkingDays(long school, long teacher, List<Integer> days) {
        catalog.lockSchool(school);
        if (catalog.forSchool(school).teachers().stream().noneMatch(t -> t.id() == teacher))
            throw new ScheduleException("Учитель не найден в этой школе.");
        if (days == null || days.isEmpty() || days.stream().anyMatch(d -> d == null || d < 1 || d > 5))
            throw new ScheduleException("Выберите хотя бы один рабочий день с понедельника по пятницу.");
        jdbc.update("delete from schedule_teacher_unavailable where school_id=? and teacher_id=?", school, teacher);
        for (int day = 1; day <= 5; day++) {
            if (!days.contains(day)) for (int slot = 1; slot <= 6; slot++)
                jdbc.update("insert into schedule_teacher_unavailable values(?,?,?,?)", school, teacher, day, slot);
        }
    }
    public record Settings(int dailyLimit, int daysPerWeek) {}

    public Settings settings(long school) {
        return jdbc.query("select daily_limit, days_per_week from schedule_planning_settings where school_id=?",
                (rs, n) -> new Settings(rs.getInt(1), rs.getInt(2)), school).stream().findFirst().orElse(new Settings(6, 5));
    }

    public List<GenerationForm.Unavailable> unavailable(long school) {
        return jdbc.query("select teacher_id,weekday,lesson_number from schedule_teacher_unavailable where school_id=? order by teacher_id,weekday,lesson_number",
                (rs, n) -> new GenerationForm.Unavailable(rs.getLong(1), rs.getInt(2), rs.getInt(3)), school);
    }

    public List<GenerationForm.Load> loads(long school) {
        return jdbc.query("select * from schedule_curriculum where school_id=? order by class_id,subject_id", (rs, n) -> {
            var row = new GenerationForm.Load();
            row.setClassId(rs.getLong("class_id"));
            row.setSubjectId(rs.getLong("subject_id"));
            row.setTeacherId(rs.getObject("teacher_id", Long.class));
            row.setRoomId(rs.getObject("room_id", Long.class));
            row.setWeeklyCount(rs.getInt("weekly_count"));
            return row;
        }, school);
    }

    public List<ClassSummary> summary(long school) {
        var rows = loads(school);
        return catalog.forSchool(school).classes().stream().map(c -> new ClassSummary(c.id(), c.name(),
                rows.stream().filter(l -> l.getClassId() == c.id()).mapToInt(GenerationForm.Load::getWeeklyCount).sum(),
                (int) rows.stream().filter(l -> l.getClassId() == c.id()).count())).toList();
    }

    public GenerationForm classForm(long school, long classId) {
        requireClass(school, classId);
        var saved = loads(school).stream().filter(l -> l.getClassId() == classId).toList();
        var form = new GenerationForm();
        form.setLoads(catalog.forSchool(school).subjects().stream().map(subject -> {
            var existing = saved.stream().filter(l -> l.getSubjectId() == subject.id()).findFirst();
            if (existing.isPresent()) return existing.get();
            var row = new GenerationForm.Load();
            row.setClassId(classId);
            row.setSubjectId(subject.id());
            row.setWeeklyCount(0);
            var assigned = jdbc.queryForList("select distinct teacher_id from teaching_assignment where school_id=? and class_id=? and subject_id=?",
                    Long.class, school, classId, subject.id());
            if (assigned.size() == 1) row.setTeacherId(assigned.getFirst());
            return row;
        }).toList());
        return form;
    }

    @Transactional
    public void saveClass(long school, long classId, GenerationForm form) {
        catalog.lockSchool(school);
        requireClass(school, classId);
        var refs = catalog.forSchool(school);
        if (form.getLoads() == null || form.getLoads().size() > 200) throw new ScheduleException("Слишком много предметов.");
        Set<Long> subjects = new HashSet<>();
        int total = 0;
        for (var row : form.getLoads()) {
            if (row == null || row.getSubjectId() == null || !subjects.add(row.getSubjectId())
                    || refs.subjects().stream().noneMatch(s -> s.id() == row.getSubjectId())
                    || row.getWeeklyCount() == null || row.getWeeklyCount() < 0 || row.getWeeklyCount() > 30) {
                throw new ScheduleException("Проверьте предметы и часы: от 0 до 30, без повторов.");
            }
            total += row.getWeeklyCount();
            if (row.getTeacherId() != null && catalog.teachingAssignments(school).stream().noneMatch(a ->
                    a.teacherId() == row.getTeacherId() && a.subjectId() == row.getSubjectId())) {
                throw new ScheduleException("Для предмета «" + refs.name(refs.subjects(), row.getSubjectId()) + "» выбран неподходящий преподаватель.");
            }
            if (row.getRoomId() != null && refs.rooms().stream().noneMatch(r -> r.id() == row.getRoomId()))
                throw new ScheduleException("Кабинет не найден в этой школе.");
        }
        if (total > 30) throw new ScheduleException("У класса не может быть больше 30 уроков в неделю.");
        jdbc.update("delete from schedule_curriculum where school_id=? and class_id=?", school, classId);
        for (var row : form.getLoads()) {
            if (row.getWeeklyCount() > 0) jdbc.update("insert into schedule_curriculum(school_id,class_id,subject_id,teacher_id,room_id,weekly_count) values(?,?,?,?,?,?)",
                    school, classId, row.getSubjectId(), row.getTeacherId(), row.getRoomId(), row.getWeeklyCount());
        }
    }

    @Transactional
    public void copy(long school, long source, long target) {
        catalog.lockSchool(school);
        requireClass(school, source);
        requireClass(school, target);
        if (source == target) throw new ScheduleException("Выберите другой класс для копирования.");
        var form = classForm(school, source);
        if (form.getLoads().stream().noneMatch(l -> l.getWeeklyCount() > 0)) throw new ScheduleException("В исходном классе ещё нет учебного плана.");
        saveClass(school, target, form);
    }

    @Transactional
    public void saveSettings(long school, int dailyLimit, int daysPerWeek) {
        if (dailyLimit < 1 || dailyLimit > 6 || daysPerWeek < 1 || daysPerWeek > 5)
            throw new ScheduleException("Выберите от 1 до 6 уроков в день и от 1 до 5 учебных дней.");
        catalog.lockSchool(school);
        jdbc.update("delete from schedule_planning_settings where school_id=?", school);
        jdbc.update("insert into schedule_planning_settings values(?,?,?)", school, dailyLimit, daysPerWeek);
    }

    @Transactional
    public void setUnavailable(long school, long teacher, int weekday, int slot, boolean remove) {
        catalog.lockSchool(school);
        if (catalog.forSchool(school).teachers().stream().noneMatch(t -> t.id() == teacher)
                || weekday < 1 || weekday > 5 || slot < 1 || slot > 6) throw new ScheduleException("Проверьте учителя, день и номер урока.");
        jdbc.update("delete from schedule_teacher_unavailable where school_id=? and teacher_id=? and weekday=? and lesson_number=?", school, teacher, weekday, slot);
        if (!remove) jdbc.update("insert into schedule_teacher_unavailable values(?,?,?,?)", school, teacher, weekday, slot);
    }

    public GenerationForm schoolForm(long school, LocalDate start, LocalDate end) {
        var form = new GenerationForm();
        form.setStart(start);
        form.setEnd(end);
        form.setLoads(loads(school));
        var settings = settings(school);
        form.setDailyLimit(settings.dailyLimit());
        form.setDaysPerWeek(settings.daysPerWeek());
        form.setUnavailable(unavailable(school));
        return form;
    }

    public List<String> problems(long school) {
        var refs = catalog.forSchool(school);
        var settings = settings(school);
        var loads = loads(school);
        var problems = new ArrayList<String>();
        if (refs.classes().isEmpty()) problems.add("Добавьте классы школы в разделе управления.");
        var classLimits = classLimits(school);
        var teacherLimits = teacherLimits(school);
        for (var c : summary(school)) {
            if (c.hours() > classLimits.getOrDefault(c.id(), 30))
                problems.add(c.name() + ": в плане " + c.hours() + " уроков, недельный лимит — " + classLimits.get(c.id()) + ". Уменьшите часы в плане или измените лимит в управлении.");
            if (c.hours() == 0 && classLimits.getOrDefault(c.id(), 30) > 0) problems.add(c.name() + ": заполните учебный план.");
            if (c.hours() > settings.dailyLimit() * settings.daysPerWeek()) problems.add(c.name() + ": нагрузка превышает выбранную учебную неделю.");
        }
        Map<Long, Integer> teacherHours = new HashMap<>();
        for (var row : loads) {
            String label = refs.name(refs.classes(), row.getClassId()) + " · " + refs.name(refs.subjects(), row.getSubjectId());
            if (row.getTeacherId() == null) problems.add(label + ": выберите учителя.");
            else {
                teacherHours.merge(row.getTeacherId(), row.getWeeklyCount(), Integer::sum);
                if (catalog.teachingAssignments(school).stream().noneMatch(a -> a.teacherId() == row.getTeacherId() && a.subjectId() == row.getSubjectId()))
                    problems.add(label + ": учитель больше не назначен на этот предмет.");
            }
            int size = refs.classes().stream().filter(c -> c.id() == row.getClassId()).findFirst().orElseThrow().studentCount();
            if (refs.rooms().stream().noneMatch(r -> r.capacity() >= size && (row.getRoomId() == null || r.id() == row.getRoomId())))
                problems.add(label + ": нет подходящего кабинета по вместимости и выбранному ограничению.");
        }
        var unavailable = unavailable(school);
        teacherHours.forEach((teacher, hours) -> {
            if (hours > teacherLimits.getOrDefault(teacher, 30))
                problems.add(refs.name(refs.teachers(), teacher) + ": назначено " + hours + " уроков, недельный лимит — " + teacherLimits.get(teacher) + ". Перераспределите нагрузку или измените лимит в управлении.");
            long blocked = unavailable.stream().filter(u -> u.teacherId() == teacher && u.weekday() <= settings.daysPerWeek() && u.lessonNumber() <= settings.dailyLimit()).count();
            long available = settings.dailyLimit() * settings.daysPerWeek() - blocked;
            if (hours > available) problems.add(refs.name(refs.teachers(), teacher) + ": назначено " + hours + " ч., доступно " + available + ".");
        });
        return problems;
    }

    public String revision(long school) {
        var form = schoolForm(school, null, null);
        String rows = form.getLoads().stream().map(l -> l.getClassId() + ":" + l.getSubjectId() + ":" + l.getTeacherId() + ":" + l.getRoomId() + ":" + l.getWeeklyCount()).reduce("", (a,b) -> a + ";" + b);
        return rows + settings(school) + unavailable(school) + classLimits(school) + teacherLimits(school);
    }

    private void requireClass(long school, long classId) {
        if (catalog.forSchool(school).classes().stream().noneMatch(c -> c.id() == classId)) throw new ScheduleException("Класс не найден в этой школе.");
    }
}
