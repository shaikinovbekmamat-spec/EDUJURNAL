package com.example.edujurnal.service;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.entity.User;
import com.example.edujurnal.exception.ScheduleException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Расчёты АЛГ 02. Все значения графиков доступны также как строки таблиц. */
@Service
@Transactional(readOnly = true)
public class PerformanceAnalyticsService {
    public static final String NO_DATA = "Нет данных";
    private final JdbcTemplate jdbc;
    private final ScheduleAccessService access;
    private final Clock clock;

    public PerformanceAnalyticsService(JdbcTemplate jdbc, ScheduleAccessService access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    public enum Interval { WEEK, MONTH }
    public enum Trend { NO_DATA, GROWTH, ATTENTION, STABLE }
    /** Год и четверть задаются идентификаторами; даты пересекаются с их границами. */
    public record Filter(Long studentId, Long yearId, Long quarterId, LocalDate from,
                         LocalDate to, Long subjectId, Interval interval) {}
    public record Value(BigDecimal value, long count) {
        public String label() { return value == null ? NO_DATA : value.toPlainString(); }
    }
    public record Average(long subjectId, String subject, Value average) {}
    public record Point(LocalDate from, LocalDate to, long count, BigDecimal average,
                        boolean completed, Trend trend, BigDecimal change) {
        public String label() { return average == null ? NO_DATA : average.toPlainString(); }
    }
    public record SubjectDynamics(long subjectId, String subject, List<Point> points) {}
    public record Attention(long subjectId, String subject, Value average, String reason,
                            LocalDate previousFrom, LocalDate previousTo,
                            LocalDate currentFrom, LocalDate currentTo, BigDecimal change) {}
    public record Attendance(LocalDate from, LocalDate to, long present, long absent,
                             long late, long unmarked, Value absencePercent) {}
    public record Homework(long notSubmitted, long sent, long returned, long accepted,
                           Value acceptedPercent) {
        public Map<String, Long> statuses() {
            Map<String, Long> result = new LinkedHashMap<>();
            result.put("Не сдано", notSubmitted);
            result.put("Отправлено", sent);
            result.put("На доработке", returned);
            result.put("Принято", accepted);
            return Collections.unmodifiableMap(result);
        }
    }
    public record Report(LocalDate from, LocalDate to, Interval interval, Value average,
                         List<Average> averages, List<SubjectDynamics> dynamics,
                         List<Attention> attention, List<Attendance> attendance,
                         Homework homework) {
        public String attentionLabel() { return attention.isEmpty() ?
                (average.count() == 0 ? NO_DATA : "Предметов внимания нет") : "Предметы внимания"; }
    }
    private record Range(LocalDate from, LocalDate to) {}
    private record Grade(long subjectId, String subject, LocalDate date, int value) {}
    private record Subject(long id, String name) {}
    private record AttendanceMark(LocalDate date, String status) {}
    public record Option(long id, String label) {}
    public record Page(Report report, List<Option> students, List<Option> years,
                       List<Option> quarters, List<Option> subjects) {}

    public Page build(Authentication auth, Filter filter) {
        User user = access.currentUser(auth);
        String visible = scope(user, null);
        String selected = scope(user, filter.studentId());
        List<Option> students = jdbc.query("select u.id,u.full_name from users u where " + visible
                + " order by u.full_name,u.id", (r,n) -> new Option(r.getLong(1), r.getString(2)));
        String schools = "exists(select 1 from users u where " + selected + " and u.school_id=y.school_id)";
        List<Option> years = jdbc.query("select y.id,y.name from academic_year y where " + schools
                + " order by y.starts_on desc,y.id", (r,n) -> new Option(r.getLong(1), r.getString(2)));
        String quarterYear = filter.yearId() == null ? "" : " and y.id=" + positive(filter.yearId());
        List<Option> quarters = jdbc.query("select q.id,y.name,q.number from academic_quarter q "
                + "join academic_year y on y.id=q.year_id where " + schools + quarterYear
                + " order by y.starts_on desc,q.number", (r,n) -> new Option(r.getLong(1), r.getString(2) + " · " + r.getInt(3) + " четверть"));
        List<Option> subjects = jdbc.query("select s.id,s.name from schedule_subject s where exists "
                + "(select 1 from users u where " + selected + " and u.school_id=s.school_id) order by s.name,s.id",
                (r,n) -> new Option(r.getLong(1), r.getString(2)));
        if (filter.subjectId() != null && subjects.stream().noneMatch(s -> s.id() == filter.subjectId()))
            throw new AccessDeniedException("Предмет недоступен.");
        return new Page(calculate(auth, filter), students, years, quarters, subjects);
    }
    private static final class Sum {
        long sum;
        long count;
        void add(int value) { sum += value; count++; }
        BigDecimal average() { return count == 0 ? null : BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP); }
        Value value() { return new Value(average(), count); }
    }

    public Report calculate(Authentication auth, Filter requested) {
        User user = access.currentUser(auth);
        Filter f = requested == null ? new Filter(null, null, null, null, null, null, Interval.WEEK) : requested;
        Interval interval = f.interval() == null ? Interval.WEEK : f.interval();
        // Проверка выбранного ученика выполняется даже при пустом периоде.
        String scope = scope(user, f.studentId());
        LocalDateTime now = LocalDateTime.now(clock);
        Range range = range(user, f, scope, now.toLocalDate());
        String subject = f.subjectId() == null ? "" : " and l.subject_id=" + positive(f.subjectId());
        List<Grade> grades = jdbc.query("""
                select l.subject_id,s.name,l.lesson_date,g.grade_value
                from journal_grade g join users u on u.id=g.student_id
                join schedule_lesson l on l.id=g.lesson_id and l.school_id=u.school_id
                join schedule_subject s on s.id=l.subject_id
                where
                """ + scope + " and l.lesson_date between ? and ?" + subject,
                (r, n) -> new Grade(r.getLong(1), r.getString(2), r.getObject(3, LocalDate.class), r.getInt(4)),
                range.from(), range.to());
        Map<Subject, List<Grade>> grouped = new TreeMap<>(Comparator.comparing(Subject::name).thenComparingLong(Subject::id));
        Sum total = new Sum();
        for (Grade g : grades) {
            grouped.computeIfAbsent(new Subject(g.subjectId(), g.subject()), k -> new ArrayList<>()).add(g);
            total.add(g.value());
        }
        List<Range> buckets = buckets(range, interval);
        List<Average> averages = new ArrayList<>();
        List<SubjectDynamics> dynamics = new ArrayList<>();
        List<Attention> attention = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            Subject s = entry.getKey();
            Sum sum = sum(entry.getValue(), range);
            averages.add(new Average(s.id(), s.name(), sum.value()));
            List<Sum> sums = buckets.stream().map(b -> sum(entry.getValue(), b)).toList();
            List<Point> points = new ArrayList<>();
            int lastCompleted = -1;
            for (int i = 0; i < buckets.size(); i++) {
                Range b = buckets.get(i);
                Sum current = sums.get(i);
                boolean complete = !b.from().isBefore(range.from()) && !b.to().isAfter(range.to())
                        && b.to().isBefore(now.toLocalDate());
                Trend trend = Trend.NO_DATA;
                BigDecimal change = null;
                if (complete) lastCompleted = i;
                if (complete && i > 0 && points.get(i - 1).completed()
                        && current.count >= 3 && sums.get(i - 1).count >= 3) {
                    Sum previous = sums.get(i - 1);
                    int direction = compareChange(current, previous);
                    trend = direction > 0 ? Trend.GROWTH : direction < 0 ? Trend.ATTENTION : Trend.STABLE;
                    change = difference(current, previous);
                }
                points.add(new Point(b.from(), b.to(), current.count, current.average(), complete, trend, change));
            }
            dynamics.add(new SubjectDynamics(s.id(), s.name(), List.copyOf(points)));
            boolean low = sum.count >= 3 && sum.sum < 3 * sum.count;
            Point last = lastCompleted < 0 ? null : points.get(lastCompleted);
            boolean falling = last != null && last.trend() == Trend.ATTENTION;
            if (low || falling) {
                Range previous = falling ? buckets.get(lastCompleted - 1) : null;
                attention.add(new Attention(s.id(), s.name(), sum.value(),
                        low && falling ? "Средний балл < 3; снижение ≥ 0,5" : low ? "Средний балл < 3" : "Снижение ≥ 0,5",
                        previous == null ? null : previous.from(), previous == null ? null : previous.to(),
                        falling ? last.from() : range.from(), falling ? last.to() : range.to(),
                        falling ? last.change() : null));
            }
        }
        List<AttendanceMark> marks = jdbc.query("""
                select l.lesson_date,a.status from schedule_lesson l
                join users u on u.school_id=l.school_id and u.role='STUDENT'
                left join attendance a on a.lesson_id=l.id and a.student_id=u.id
                where
                """ + scope + " and l.lesson_date between ? and ? and (l.lesson_date+l.start_time)<=?" + subject + """
                 and (a.student_id is not null or (u.student_class_id=l.class_id and
                   (l.subgroup_id is null or exists(select 1 from schedule_membership m
                    where m.subgroup_id=l.subgroup_id and m.student_id=u.id
                    and m.active_from<=l.lesson_date and (m.active_to is null or m.active_to>=l.lesson_date)))))
                """, (r, n) -> new AttendanceMark(r.getObject(1, LocalDate.class), r.getString(2)),
                range.from(), range.to(), now);
        List<Attendance> attendance = new ArrayList<>();
        for (Range b : buckets) {
            long present = 0, absent = 0, late = 0, unmarked = 0;
            for (AttendanceMark m : marks) if (contains(b, m.date())) {
                if ("PRESENT".equals(m.status())) present++;
                else if ("ABSENT".equals(m.status())) absent++;
                else if ("LATE".equals(m.status())) late++;
                else unmarked++;
            }
            attendance.add(new Attendance(b.from().isBefore(range.from()) ? range.from() : b.from(),
                    b.to().isAfter(range.to()) ? range.to() : b.to(), present, absent, late, unmarked,
                    percent(absent, present + absent + late)));
        }
        String homeworkSubject = f.subjectId() == null ? "" : " and h.subject_id=" + positive(f.subjectId());
        List<String> statuses = jdbc.query("""
                select case when h.mode='NOTEBOOK' then
                   case when p.student_id is null then 'NOT_SUBMITTED' else 'ACCEPTED' end
                   else coalesce((select hs.status from homework_submission hs
                     where hs.homework_id=h.id and hs.student_id=u.id
                     order by hs.version desc limit 1),'NOT_SUBMITTED') end
                from homework h join homework_audience ha on ha.homework_id=h.id
                join users u on u.id=ha.student_id and u.school_id=h.school_id
                left join homework_paper_mark p on p.homework_id=h.id and p.student_id=u.id
                where
                """ + scope + " and h.published_at>=? and h.published_at<? and h.published_at<=?" + homeworkSubject,
                (r, n) -> r.getString(1), range.from().atStartOfDay(clock.getZone()).toOffsetDateTime(),
                range.to().plusDays(1).atStartOfDay(clock.getZone()).toOffsetDateTime(), OffsetDateTime.now(clock));
        long notSubmitted = count(statuses, "NOT_SUBMITTED"), sent = count(statuses, "SENT"),
                returned = count(statuses, "RETURNED"), accepted = count(statuses, "ACCEPTED");
        return new Report(range.from(), range.to(), interval, total.value(), List.copyOf(averages),
                List.copyOf(dynamics), List.copyOf(attention), List.copyOf(attendance),
                new Homework(notSubmitted, sent, returned, accepted, percent(accepted, statuses.size())));
    }

    /** Область доступа применяется к каждой исходной выборке, а не только к фильтру. */
    private String scope(User user, Long studentId) {
        String sql = "u.role='STUDENT'";
        if (user.getRole() == Role.STUDENT) sql += " and u.id=" + positive(user.getId());
        else if (user.getRole() == Role.PARENT) sql += " and exists(select 1 from parent_student ps where ps.student_id=u.id and ps.parent_id=" + positive(user.getId()) + ")";
        else if (user.getRole() == Role.TEACHER || user.getRole() == Role.DIRECTOR) {
            if (user.getSchool() == null) throw new AccessDeniedException("Школа не назначена.");
            sql += " and u.school_id=" + positive(user.getSchool().getId());
            if (user.getRole() == Role.TEACHER) sql += " and exists(select 1 from class_teacher ct where ct.school_id=u.school_id and ct.class_id=u.student_class_id and ct.teacher_user_id=" + positive(user.getId()) + ")";
        } else throw new AccessDeniedException("Аналитика недоступна для этой роли.");
        if (studentId != null) {
            if (jdbc.queryForObject("select count(*) from users u where " + sql + " and u.id=?", Long.class, studentId) == 0)
                throw new AccessDeniedException("Нет доступа к аналитике этого ученика.");
            sql += " and u.id=" + positive(studentId);
        }
        return sql;
    }

    private Range range(User user, Filter f, String scope, LocalDate today) {
        if (f.from() != null && f.to() != null && f.from().isAfter(f.to()))
            throw new ScheduleException("Начало периода позже окончания.");
        LocalDate from = null, to = null;
        Long yearId = f.yearId();
        if (f.quarterId() != null) {
            List<Long> years = jdbc.queryForList("select year_id from academic_quarter where id=?", Long.class, f.quarterId());
            if (years.isEmpty() || yearId != null && !yearId.equals(years.getFirst()))
                throw new ScheduleException("Четверть не относится к выбранному году.");
            yearId = years.getFirst();
        }
        if (yearId != null) {
            List<Range> years = jdbc.query("select y.starts_on,y.ends_on from academic_year y where y.id=? and "
                    + "(exists(select 1 from users u where " + scope + " and u.school_id=y.school_id)"
                    + " or y.school_id=?)", (r, n) -> new Range(r.getObject(1, LocalDate.class), r.getObject(2, LocalDate.class)),
                    yearId, user.getRole() == Role.PARENT || user.getSchool() == null ? null : user.getSchool().getId());
            if (years.isEmpty()) throw new AccessDeniedException("Учебный год недоступен.");
            from = years.getFirst().from(); to = years.getFirst().to();
        }
        if (f.quarterId() != null) {
            Range q = jdbc.queryForObject("select starts_on,ends_on from academic_quarter where id=?",
                    (r, n) -> new Range(r.getObject(1, LocalDate.class), r.getObject(2, LocalDate.class)), f.quarterId());
            from = later(from, q.from()); to = earlier(to, q.to());
        }
        from = later(from, f.from()); to = earlier(to, f.to());
        if (from == null) from = LocalDate.of(today.getMonthValue() >= 9 ? today.getYear() : today.getYear() - 1, 9, 1);
        if (to == null) to = today;
        if (from.isAfter(to)) throw new ScheduleException("Выбранные периоды не пересекаются.");
        // Если родитель видит детей разных школ, выбранный год ограничивает и состав учеников.
        if (yearId != null && jdbc.queryForObject("select count(distinct u.school_id) from users u where " + scope
                + " and u.school_id<>(select school_id from academic_year where id=?)", Long.class, yearId) > 0)
            throw new ScheduleException("Для выбора учебного года укажите ученика из соответствующей школы.");
        return new Range(from, to);
    }

    private static long positive(Long id) {
        if (id == null || id <= 0) throw new ScheduleException("Некорректный идентификатор.");
        return id;
    }
    private static LocalDate later(LocalDate a, LocalDate b) { return a == null ? b : b == null || a.isAfter(b) ? a : b; }
    private static LocalDate earlier(LocalDate a, LocalDate b) { return a == null ? b : b == null || a.isBefore(b) ? a : b; }
    private static boolean contains(Range r, LocalDate date) { return !date.isBefore(r.from()) && !date.isAfter(r.to()); }
    private static Sum sum(List<Grade> grades, Range range) {
        Sum result = new Sum();
        for (Grade g : grades) if (contains(range, g.date())) result.add(g.value());
        return result;
    }
    private static List<Range> buckets(Range range, Interval interval) {
        List<Range> result = new ArrayList<>();
        LocalDate start = interval == Interval.MONTH ? range.from().withDayOfMonth(1)
                : range.from().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        while (!start.isAfter(range.to())) {
            LocalDate next = interval == Interval.MONTH ? start.plusMonths(1) : start.plusWeeks(1);
            result.add(new Range(start, next.minusDays(1))); start = next;
        }
        return result;
    }
    // Сравнение дробей до округления: (sum1/count1 - sum0/count0) относительно ±1/2.
    private static int compareChange(Sum current, Sum previous) {
        BigDecimal numerator = BigDecimal.valueOf(current.sum).multiply(BigDecimal.valueOf(previous.count))
                .subtract(BigDecimal.valueOf(previous.sum).multiply(BigDecimal.valueOf(current.count))).multiply(BigDecimal.TWO);
        BigDecimal denominator = BigDecimal.valueOf(current.count).multiply(BigDecimal.valueOf(previous.count));
        return numerator.compareTo(denominator) >= 0 ? 1 : numerator.compareTo(denominator.negate()) <= 0 ? -1 : 0;
    }
    private static BigDecimal difference(Sum current, Sum previous) {
        return BigDecimal.valueOf(current.sum).multiply(BigDecimal.valueOf(previous.count))
                .subtract(BigDecimal.valueOf(previous.sum).multiply(BigDecimal.valueOf(current.count)))
                .divide(BigDecimal.valueOf(current.count).multiply(BigDecimal.valueOf(previous.count)), 2, RoundingMode.HALF_UP);
    }
    private static Value percent(long numerator, long denominator) {
        return new Value(denominator == 0 ? null : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP), denominator);
    }
    private static long count(List<String> statuses, String status) { return statuses.stream().filter(status::equals).count(); }
}
