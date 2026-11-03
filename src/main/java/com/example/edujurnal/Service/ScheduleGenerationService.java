package com.example.edujurnal.service;

import com.example.edujurnal.dto.GenerationForm;
import com.example.edujurnal.dto.LessonForm;
import com.example.edujurnal.entity.Lesson;
import com.example.edujurnal.exception.ScheduleException;
import com.example.edujurnal.repository.LessonRepository;
import com.example.edujurnal.repository.ScheduleCatalog;
import com.example.edujurnal.util.BellSchedule;
import com.example.edujurnal.util.AcademicTerm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ScheduleGenerationService {
    private final ScheduleCatalog catalog;
    private final LessonRepository lessons;
    private final ScheduleValidator validator;
    private final ScheduleService schedule;
    private final CurriculumService curriculum;

    public ScheduleGenerationService(ScheduleCatalog catalog, LessonRepository lessons,
                                     ScheduleValidator validator, ScheduleService schedule, CurriculumService curriculum) {
        this.catalog = catalog;
        this.lessons = lessons;
        this.validator = validator;
        this.schedule = schedule;
        this.curriculum = curriculum;
    }

    public record Plan(long schoolId, LocalDate start, LocalDate end, List<LessonForm> entries, int lessonCount,
                       Map<Long, Long> replaced, String curriculumRevision, Set<String> pinned) {
        public String day(LessonForm entry) {
            return entry.getDate().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru"));
        }
        public List<LessonForm> cell(String view, long id, int weekday, int number) {
            return entries.stream().filter(e -> (view.equals("teacher") ? e.getTeacherId() == id : e.getClassId() == id) && e.getDate().getDayOfWeek().getValue() == weekday
                    && e.getLessonNumber() == number).toList();
        }
    }
    private record Candidate(LessonForm first, int count) {}
    private record Task(String label, List<Candidate> candidates) {}

    @Transactional(readOnly = true)
    public Plan preview(long schoolId, GenerationForm input) {
        return calculate(schoolId, input, false, List.of());
    }

    @Transactional(readOnly = true)
    public Plan previewSchool(long schoolId, LocalDate start, LocalDate end) {
        var problems = curriculum.problems(schoolId);
        if (!problems.isEmpty()) throw new ScheduleException(problems);
        return calculate(schoolId, curriculum.schoolForm(schoolId, start, end), true, List.of());
    }

    private Plan calculate(long schoolId, GenerationForm input, boolean replace, List<LessonForm> fixed) {
        if (input.getStart() == null || input.getEnd() == null
                || !input.getStart().isAfter(schedule.today()) || input.getEnd().isBefore(input.getStart())
                || input.getEnd().isAfter(input.getStart().plusMonths(6))) {
            throw new ScheduleException("Выберите будущий период продолжительностью не более шести месяцев.");
        }
        if (input.getLoads() == null || input.getLoads().isEmpty() || input.getLoads().size() > 1000) {
            throw new ScheduleException("Заполните учебные планы классов (до 1000 предметных строк).");
        }
        var term = AcademicTerm.containing(input.getStart());
        if (input.getStart().isBefore(term.start()) || input.getEnd().isAfter(term.end())) {
            throw new ScheduleException("Период должен находиться в одном учебном полугодии: январь–май или сентябрь–декабрь.");
        }
        var refs = catalog.forSchool(schoolId);
        var current = lessons.findBySchoolIdAndDateBetweenOrderByDateAscStartTimeAscIdAsc(schoolId, input.getStart(), input.getEnd());
        if (replace && current.stream().anyMatch(l -> !schedule.editable(l)))
            throw new ScheduleException("В выбранном периоде есть уроки с оценками или начавшиеся занятия. Выберите другой период.");
        var existing = (replace ? List.<Lesson>of() : current).stream().collect(Collectors.groupingBy(Lesson::getDate));
        List<Task> tasks = new ArrayList<>();
        List<Candidate> selected = new ArrayList<>();
        Occupancy occupied = new Occupancy();
        Map<Long, Integer> classLoad = new HashMap<>();
        Map<Long, Integer> teacherLoad = new HashMap<>();
        var classLimits = curriculum.classLimits(schoolId);
        var teacherLimits = curriculum.teacherLimits(schoolId);
        Set<String> unique = new HashSet<>();
        for (var load : input.getLoads()) {
            if (load == null || load.getClassId() == null || load.getSubjectId() == null || load.getTeacherId() == null
                    || load.getWeeklyCount() == null || load.getWeeklyCount() < 1 || load.getWeeklyCount() > 30) {
                throw new ScheduleException("В каждой строке выберите класс, предмет, учителя и от 1 до 30 уроков в неделю.");
            }
            if (!unique.add(load.getClassId() + ":" + load.getSubjectId())) {
                throw new ScheduleException("Укажите нагрузку по предмету для класса одной строкой.");
            }
            if (classLoad.merge(load.getClassId(), load.getWeeklyCount(), Integer::sum) > 30
                    || teacherLoad.merge(load.getTeacherId(), load.getWeeklyCount(), Integer::sum) > 30) {
                throw new ScheduleException("Нагрузка класса или учителя превышает 30 уроков в неделю.");
            }
            if (classLoad.get(load.getClassId()) > classLimits.getOrDefault(load.getClassId(), 30))
                throw new ScheduleException(refs.name(refs.classes(), load.getClassId()) + ": превышен недельный лимит класса — " + classLimits.get(load.getClassId()) + " уроков.");
            if (teacherLoad.get(load.getTeacherId()) > teacherLimits.getOrDefault(load.getTeacherId(), 30))
                throw new ScheduleException(refs.name(refs.teachers(), load.getTeacherId()) + ": превышен недельный лимит учителя — " + teacherLimits.get(load.getTeacherId()) + " уроков.");
            String label = refs.name(refs.classes(), load.getClassId()) + " · " + refs.name(refs.subjects(), load.getSubjectId());
            var schoolClass = refs.classes().stream().filter(c -> c.id() == load.getClassId()).findFirst()
                    .orElseThrow(() -> new ScheduleException("Класс не найден в этой школе."));
            var rooms = refs.rooms().stream().filter(r -> r.capacity() >= schoolClass.studentCount()
                            && (load.getRoomId() == null || r.id() == load.getRoomId()))
                    .sorted(Comparator.comparing(ScheduleCatalog.Option::capacity).thenComparingLong(ScheduleCatalog.Option::id)).toList();
            if (rooms.isEmpty()) throw new ScheduleException(label + ": нет кабинета достаточной вместимости.");
            LessonForm probe = form(load, input.getStart(), BellSchedule.SLOTS.getFirst(), rooms.getFirst().id());
            catalog.validate(schoolId, probe);
            List<Candidate> candidates = new ArrayList<>();
            for (int offset = 0; offset < 7; offset++) {
                LocalDate first = input.getStart().plusDays(offset);
                if (first.isAfter(input.getEnd()) || first.getDayOfWeek().getValue() > input.getDaysPerWeek()) continue;
                for (var slot : BellSchedule.SLOTS) {
                    if (slot.number() > input.getDailyLimit() || input.getUnavailable().stream().anyMatch(u ->
                            u.teacherId() == load.getTeacherId() && u.weekday() == first.getDayOfWeek().getValue()
                                    && u.lessonNumber() == slot.number())) continue;
                    for (var room : rooms) {
                        boolean available = true;
                        for (LocalDate date = first; !existing.isEmpty() && !date.isAfter(input.getEnd()); date = date.plusWeeks(1)) {
                            var entry = form(load, date, slot, room.id());
                            if (!validator.checkConflicts(entry, null, existing.getOrDefault(date, List.of())).isEmpty()) {
                                available = false;
                                break;
                            }
                        }
                        int count = (int) (java.time.temporal.ChronoUnit.DAYS.between(first, input.getEnd()) / 7 + 1);
                        if (available) candidates.add(new Candidate(form(load, first, slot, room.id()), count));
                    }
                }
            }
            if (candidates.isEmpty()) throw new ScheduleException(label + ": нет свободного времени и кабинета на весь период.");
            var pinned = fixed.stream().filter(e -> e.getClassId().equals(load.getClassId()) && e.getSubjectId().equals(load.getSubjectId())).toList();
            if (pinned.size() > load.getWeeklyCount()) throw new ScheduleException(label + ": закреплено больше уроков, чем указано в плане.");
            for (var entry : pinned) {
                var valid = candidates.stream().filter(c -> c.first().getDate().equals(entry.getDate())
                        && c.first().getLessonNumber().equals(entry.getLessonNumber()) && c.first().getRoomId().equals(entry.getRoomId())
                        && c.first().getTeacherId().equals(entry.getTeacherId())).findFirst()
                        .orElseThrow(() -> new ScheduleException(label + ": закреплённый урок не соответствует ограничениям."));
                if (!occupied.available(entry)) throw new ScheduleException("Закреплённые уроки пересекаются.");
                occupied.toggle(entry);
                selected.add(new Candidate(entry.copy(), valid.count()));
            }
            for (int i = pinned.size(); i < load.getWeeklyCount(); i++) tasks.add(new Task(label, candidates));
        }
        if (tasks.size() > 1000) throw new ScheduleException("За один запуск можно распределить до 1000 уроков в неделю.");
        tasks.sort(Comparator.comparingInt(t -> t.candidates().size()));
        int[] budget = {100_000};
        if (!search(tasks, 0, selected, budget, occupied, new IdentityHashMap<>())) {
            throw new ScheduleException(budget[0] <= 0
                    ? "Не удалось завершить поиск расписания. Проверьте рабочие дни учителей и закреплённые кабинеты; при необходимости расширьте доступные дни."
                    : "Не удалось разместить всю нагрузку без пересечений. Проверьте нагрузку учителей, классов и доступные кабинеты.");
        }
        List<LessonForm> entries = selected.stream().map(c -> c.first().copy())
                .sorted(Comparator.comparing(LessonForm::getDate).thenComparing(LessonForm::getStartTime)
                        .thenComparing(LessonForm::getClassId)).toList();
        entries.forEach(e -> { if (e.getRequestToken() == null) e.setRequestToken(UUID.randomUUID().toString()); });
        return new Plan(schoolId, input.getStart(), input.getEnd(), entries, selected.stream().mapToInt(Candidate::count).sum(),
                replace ? revision(current) : null, replace ? curriculum.revision(schoolId) : null,
                fixed.stream().map(LessonForm::getRequestToken).collect(Collectors.toUnmodifiableSet()));
    }

    private static class Occupancy {
        final Map<Long, Integer> classes = new HashMap<>(), teachers = new HashMap<>(), rooms = new HashMap<>();
        int bit(LessonForm f) { return 1 << ((f.getDate().getDayOfWeek().getValue() - 1) * 6 + f.getLessonNumber() - 1); }
        boolean available(LessonForm f) {
            return ((classes.getOrDefault(f.getClassId(), 0) | teachers.getOrDefault(f.getTeacherId(), 0)
                    | rooms.getOrDefault(f.getRoomId(), 0)) & bit(f)) == 0;
        }
        void toggle(LessonForm f) {
            int bit = bit(f);
            classes.merge(f.getClassId(), bit, (a,b) -> a ^ b);
            teachers.merge(f.getTeacherId(), bit, (a,b) -> a ^ b);
            rooms.merge(f.getRoomId(), bit, (a,b) -> a ^ b);
        }
        int dailyCount(LessonForm f) {
            return Integer.bitCount(classes.getOrDefault(f.getClassId(), 0) & (63 << ((f.getDate().getDayOfWeek().getValue() - 1) * 6)));
        }
    }

    private boolean search(List<Task> tasks, int index, List<Candidate> selected, int[] budget, Occupancy occupied,
                           Map<List<Candidate>, Integer> floors) {
        if (index == tasks.size()) return true;
        Map<List<Candidate>, Integer> remaining = new IdentityHashMap<>();
        for (int i = index; i < tasks.size(); i++) remaining.merge(tasks.get(i).candidates(), 1, Integer::sum);
        Set<List<Candidate>> inspected = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Long, Integer> classSlots = new HashMap<>(), teacherSlots = new HashMap<>();
        Map<Long, Integer> classNeed = new HashMap<>(), teacherNeed = new HashMap<>();
        List<Integer> options = null;
        int next = index, bestSlack = Integer.MAX_VALUE;
        for (int i = index; i < tasks.size(); i++) {
            var domain = tasks.get(i).candidates();
            if (!inspected.add(domain)) continue;
            List<Integer> available = new ArrayList<>();
            int slots = 0;
            for (int c = floors.getOrDefault(domain, -1) + 1; c < domain.size(); c++) {
                var entry = domain.get(c).first();
                if (occupied.available(entry)) {
                    available.add(c);
                    slots |= occupied.bit(entry);
                }
            }
            int need = remaining.get(domain), slack = Integer.bitCount(slots) - need;
            if (slack < 0) return false;
            var first = domain.getFirst().first();
            classSlots.merge(first.getClassId(), slots, (a,b) -> a | b);
            teacherSlots.merge(first.getTeacherId(), slots, (a,b) -> a | b);
            classNeed.merge(first.getClassId(), need, Integer::sum);
            teacherNeed.merge(first.getTeacherId(), need, Integer::sum);
            if (options == null || slack < bestSlack || (slack == bestSlack && available.size() < options.size())) {
                options = available;
                next = i;
                bestSlack = slack;
            }
        }
        // Count distinct time slots, not rooms: twenty free rooms at one time
        // still allow only one lesson for a given class or teacher.
        for (var need : classNeed.entrySet())
            if (Integer.bitCount(classSlots.get(need.getKey())) < need.getValue()) return false;
        for (var need : teacherNeed.entrySet())
            if (Integer.bitCount(teacherSlots.get(need.getKey())) < need.getValue()) return false;
        var domain = tasks.get(next).candidates();
        options.sort(Comparator.<Integer>comparingInt(c -> occupied.dailyCount(domain.get(c).first()))
                .thenComparing(c -> domain.get(c).first().getStartTime()));
        Collections.swap(tasks, index, next);
        int previousFloor = floors.getOrDefault(domain, -1);
        for (int candidateIndex : options) {
            if (--budget[0] <= 0) break;
            var candidate = domain.get(candidateIndex);
            selected.add(candidate);
            occupied.toggle(candidate.first());
            // Identical weekly occurrences are interchangeable. Visit each set
            // of placements once instead of all permutations of that set.
            floors.put(domain, candidateIndex);
            if (search(tasks, index + 1, selected, budget, occupied, floors)) return true;
            floors.put(domain, previousFloor);
            occupied.toggle(candidate.first());
            selected.removeLast();
        }
        Collections.swap(tasks, index, next);
        return false;
    }

    private boolean overlaps(LessonForm a, LessonForm b) {
        return a.getDate().equals(b.getDate()) && a.getStartTime().equals(b.getStartTime())
                && (a.getClassId().equals(b.getClassId()) || a.getTeacherId().equals(b.getTeacherId())
                || a.getRoomId().equals(b.getRoomId()));
    }

    private LessonForm form(GenerationForm.Load load, LocalDate date, BellSchedule.Slot slot, long roomId) {
        LessonForm form = new LessonForm();
        form.setClassId(load.getClassId());
        form.setSubjectId(load.getSubjectId());
        form.setTeacherId(load.getTeacherId());
        form.setRoomId(roomId);
        form.setDate(date);
        form.setLessonNumber(slot.number());
        form.setStartTime(slot.start());
        form.setEndTime(slot.end());
        return form;
    }

    @Transactional
    public int save(long schoolId, Plan plan) {
        if (plan.schoolId() != schoolId) throw new ScheduleException("Нет доступа к этой школе.");
        catalog.lockSchool(schoolId);
        if (!plan.entries().isEmpty() && plan.entries().stream().allMatch(e ->
                lessons.findByRequestTokenAndSchoolId(e.getRequestToken(), schoolId).isPresent())) return plan.lessonCount();
        if (plan.replaced() != null) {
            var current = lessons.findBySchoolIdAndDateBetweenOrderByDateAscStartTimeAscIdAsc(schoolId, plan.start(), plan.end());
            if (!revision(current).equals(plan.replaced()) || !curriculum.revision(schoolId).equals(plan.curriculumRevision()))
                throw new ScheduleException("Расписание, учебный план или ограничения изменились. Составьте новый черновик.");
            var problems = curriculum.problems(schoolId);
            if (!problems.isEmpty()) throw new ScheduleException(problems);
            if (current.stream().anyMatch(l -> !schedule.editable(l))) throw new ScheduleException("Уроки с оценками или начавшиеся занятия нельзя заменять.");
            lessons.deleteAll(current);
            lessons.flush();
        }
        int count = 0;
        for (LessonForm entry : plan.entries()) {
            LessonForm form = entry.copy();
            form.setRepeatWeekly(true);
            form.setRepeatUntil(plan.end());
            count += schedule.savePlan(schoolId, null, form).count();
        }
        return count;
    }

    private Map<Long, Long> revision(List<Lesson> current) {
        return current.stream().collect(Collectors.toMap(Lesson::getId, Lesson::getVersion));
    }

    @Transactional(readOnly = true)
    public Plan move(Plan plan, String entryToken, int weekday, int number, long roomId) {
        if (!curriculum.revision(plan.schoolId()).equals(plan.curriculumRevision()))
            throw new ScheduleException("Учебный план или ограничения изменились. Составьте новый черновик.");
        var settings = curriculum.settings(plan.schoolId());
        if (weekday < 1 || weekday > settings.daysPerWeek() || number < 1 || number > settings.dailyLimit())
            throw new ScheduleException("Это время находится за пределами учебной недели.");
        var entries = plan.entries().stream().map(LessonForm::copy).toList();
        var entry = entries.stream().filter(e -> e.getRequestToken().equals(entryToken)).findFirst()
                .orElseThrow(() -> new ScheduleException("Урок не найден в черновике."));
        LocalDate first = plan.start();
        while (first.getDayOfWeek().getValue() != weekday) first = first.plusDays(1);
        if (first.isAfter(plan.end())) throw new ScheduleException("Этот день не входит в выбранный период.");
        if (curriculum.unavailable(plan.schoolId()).stream().anyMatch(u -> u.teacherId() == entry.getTeacherId()
                && u.weekday() == weekday && u.lessonNumber() == number)) throw new ScheduleException("Учитель недоступен в это время.");
        var load = curriculum.loads(plan.schoolId()).stream().filter(l -> l.getClassId().equals(entry.getClassId())
                && l.getSubjectId().equals(entry.getSubjectId())).findFirst().orElseThrow();
        if (load.getRoomId() != null && load.getRoomId() != roomId) throw new ScheduleException("Для предмета закреплён другой кабинет в учебном плане.");
        entry.setDate(first);
        entry.setLessonNumber(number);
        entry.setStartTime(BellSchedule.get(number).start());
        entry.setEndTime(BellSchedule.get(number).end());
        entry.setRoomId(roomId);
        catalog.validate(plan.schoolId(), entry);
        if (entries.stream().anyMatch(other -> other != entry && overlaps(entry, other)))
            throw new ScheduleException("На это время уже назначен урок класса, преподавателя или кабинета.");
        int count = entries.stream().mapToInt(e -> (int) (java.time.temporal.ChronoUnit.DAYS.between(e.getDate(), plan.end()) / 7 + 1)).sum();
        return new Plan(plan.schoolId(), plan.start(), plan.end(), entries, count, plan.replaced(), plan.curriculumRevision(), plan.pinned());
    }

    public Plan pin(Plan plan, String entryToken) {
        if (plan.entries().stream().noneMatch(e -> e.getRequestToken().equals(entryToken))) throw new ScheduleException("Урок не найден в черновике.");
        Set<String> pinned = new HashSet<>(plan.pinned());
        if (!pinned.remove(entryToken)) pinned.add(entryToken);
        return new Plan(plan.schoolId(), plan.start(), plan.end(), plan.entries(), plan.lessonCount(), plan.replaced(), plan.curriculumRevision(), Set.copyOf(pinned));
    }

    @Transactional(readOnly = true)
    public Plan regenerate(Plan plan) {
        if (!curriculum.revision(plan.schoolId()).equals(plan.curriculumRevision()))
            throw new ScheduleException("Учебный план или ограничения изменились. Составьте новый черновик из учебных планов.");
        return calculate(plan.schoolId(), curriculum.schoolForm(plan.schoolId(), plan.start(), plan.end()), true,
                plan.entries().stream().filter(e -> plan.pinned().contains(e.getRequestToken())).toList());
    }
}
