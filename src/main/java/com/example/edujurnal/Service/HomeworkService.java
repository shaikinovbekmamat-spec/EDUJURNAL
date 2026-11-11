package com.example.edujurnal.service;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.entity.User;
import com.example.edujurnal.exception.ScheduleException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Домашние задания и история ответов (ФР 08–09). Все операции атомарны. */
@Service
@Transactional(readOnly = true)
public class HomeworkService {
    private static final int MAX_FILES = 5;
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final long MAX_DOCX_EXPANDED_BYTES = 20L * 1024 * 1024;
    private static final int MAX_DOCX_ENTRIES = 200;
    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final JdbcTemplate jdbc;
    private final ScheduleAccessService access;
    private final Clock clock;

    public HomeworkService(JdbcTemplate jdbc, ScheduleAccessService access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    private record Homework(long id, long schoolId, long teacherId, long classId,
                            long subjectId, String title, String body, LocalDate dueDate, String mode) {}
    private record Upload(String name, String contentType, byte[] data) {}
    private record Submission(long id, int version, String status, String comment) {}

    public record Choice(long classId, String className, long subjectId, String subject) {}
    public record Card(long id, String title, String body, String className, String subject,
                       String teacher, LocalDate dueDate, String mode, OffsetDateTime publishedAt,
                       String status, long audienceCount, long submittedCount, long acceptedCount) {
        public String modeLabel() { return "ONLINE".equals(mode) ? "На сайте" : "В тетради"; }
        public String statusLabel() { return HomeworkService.statusLabel(status); }
    }
    public record FileInfo(long id, String name, long size) {}
    public record FileDownload(String name, byte[] data) {}
    public record Answer(long id, int version, String text, String status, String comment,
                         OffsetDateTime submittedAt, OffsetDateTime checkedAt, List<FileInfo> files) {
        public String statusLabel() { return HomeworkService.statusLabel(status); }
    }
    public record StudentWork(long studentId, String name, String status, Answer latest) {
        public String statusLabel() { return HomeworkService.statusLabel(status); }
    }
    public record Detail(Card homework, boolean teacher, List<StudentWork> students, List<Answer> history) {}
    private record Visibility(String sql, Object[] args) {}

    public static String statusLabel(String status) {
        return switch (status) {
            case "SENT" -> "Отправлено";
            case "RETURNED" -> "На доработке";
            case "ACCEPTED" -> "Принято";
            default -> "Не сдано";
        };
    }

    public LocalDate today() { return LocalDate.now(clock); }

    public List<Choice> choices(Authentication auth) {
        User user = teacher(auth);
        return jdbc.query("""
                select a.class_id,c.name,a.subject_id,s.name from teaching_assignment a
                join schedule_class c on c.id=a.class_id and c.school_id=a.school_id
                join schedule_subject s on s.id=a.subject_id and s.school_id=a.school_id
                where a.school_id=? and a.teacher_id=? order by c.name,s.name
                """, (r,n) -> new Choice(r.getLong(1),r.getString(2),r.getLong(3),r.getString(4)),
                user.getSchool().getId(), user.getTeacherId());
    }

    private User member(Authentication auth) {
        User user = access.currentUser(auth);
        if ((user.getRole() != Role.TEACHER && user.getRole() != Role.STUDENT) || user.getSchool() == null)
            throw new AccessDeniedException("Домашние задания доступны учителю и ученику.");
        if (user.getRole() == Role.TEACHER && user.getTeacherId() == null)
            throw new AccessDeniedException("Аккаунт не связан с преподавателем.");
        return user;
    }

    private Visibility visible(User user) {
        if (user.getRole() == Role.TEACHER)
            return new Visibility("""
                    h.school_id=? and h.teacher_id=? and exists(select 1 from teaching_assignment a
                    where a.school_id=h.school_id and a.class_id=h.class_id
                      and a.subject_id=h.subject_id and a.teacher_id=h.teacher_id)
                    """, new Object[]{user.getSchool().getId(), user.getTeacherId()});
        return new Visibility("h.school_id=? and exists(select 1 from homework_audience a where a.homework_id=h.id and a.student_id=?)",
                new Object[]{user.getSchool().getId(), user.getId()});
    }

    private static final String CARD_SELECT = """
            select h.*,c.name as class_name,s.name as subject,t.name as teacher,
              (select count(*) from homework_audience a where a.homework_id=h.id) as audience_count,
              (select count(distinct hs.student_id) from homework_submission hs where hs.homework_id=h.id) as submitted_count,
              case when h.mode='NOTEBOOK' then
                (select count(*) from homework_paper_mark p where p.homework_id=h.id)
              else (select count(*) from homework_submission hs where hs.homework_id=h.id and hs.status='ACCEPTED'
                and hs.version=(select max(v.version) from homework_submission v
                  where v.homework_id=hs.homework_id and v.student_id=hs.student_id)) end as accepted_count
            from homework h join schedule_class c on c.id=h.class_id and c.school_id=h.school_id
            join schedule_subject s on s.id=h.subject_id and s.school_id=h.school_id
            join schedule_teacher t on t.id=h.teacher_id and t.school_id=h.school_id where
            """;

    /** Возвращает 21 строку: двадцать карточек и признак следующей страницы. */
    public List<Card> list(Authentication auth, int page) {
        User user = member(auth);
        Visibility visibility = visible(user);
        List<Object> args = new ArrayList<>(Arrays.asList(visibility.args()));
        args.add(Math.max(0, Math.min(page, 100000)) * 20);
        List<Card> cards = jdbc.query(CARD_SELECT + visibility.sql() + " order by h.published_at desc,h.id desc limit 21 offset ?",
                (r,n) -> card(r), args.toArray());
        if (user.getRole() == Role.TEACHER || cards.isEmpty()) return cards;
        // Получаем статусы всей страницы одной выборкой, без запроса для каждой карточки.
        Map<Long,String> statuses = new HashMap<>();
        List<Object> statusArgs = new ArrayList<>(List.of(user.getId(), user.getId()));
        cards.forEach(c -> statusArgs.add(c.id()));
        statusArgs.addAll(Arrays.asList(visibility.args()));
        String ids = String.join(",", java.util.Collections.nCopies(cards.size(), "?"));
        jdbc.query("""
                select h.id,case when h.mode='NOTEBOOK' then
                  case when p.student_id is null then 'NOT_SUBMITTED' else 'ACCEPTED' end
                  else coalesce((select hs.status from homework_submission hs where hs.homework_id=h.id
                    and hs.student_id=? order by hs.version desc limit 1),'NOT_SUBMITTED') end as status
                from homework h left join homework_paper_mark p on p.homework_id=h.id and p.student_id=?
                where
                """ + " h.id in (" + ids + ") and " + visibility.sql(),
                (org.springframework.jdbc.core.RowCallbackHandler) r -> statuses.put(r.getLong(1),r.getString(2)),
                statusArgs.toArray());
        return cards.stream().map(c -> withStatus(c,statuses.getOrDefault(c.id(), "NOT_SUBMITTED"))).toList();
    }

    private Card card(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Card(r.getLong("id"), r.getString("title"), r.getString("body"), r.getString("class_name"),
                r.getString("subject"),r.getString("teacher"),r.getObject("due_date",LocalDate.class),r.getString("mode"),
                localTime(r.getObject("published_at",OffsetDateTime.class)),"NOT_SUBMITTED",r.getLong("audience_count"),
                r.getLong("submitted_count"),r.getLong("accepted_count"));
    }

    private static Card withStatus(Card c, String status) {
        return new Card(c.id(),c.title(),c.body(),c.className(),c.subject(),c.teacher(),c.dueDate(),c.mode(),
                c.publishedAt(),status,c.audienceCount(),c.submittedCount(),c.acceptedCount());
    }

    public Detail detail(Authentication auth, long id) {
        User user = member(auth);
        Visibility visibility = visible(user);
        List<Object> args = new ArrayList<>(Arrays.asList(visibility.args())); args.add(id);
        List<Card> cards = jdbc.query(CARD_SELECT + visibility.sql() + " and h.id=?", (r,n) -> card(r), args.toArray());
        if (cards.isEmpty()) throw new AccessDeniedException("ДЗ недоступно.");
        boolean teacher = user.getRole() == Role.TEACHER;
        String studentFilter = teacher ? "" : " and hs.student_id=?";
        Object[] answerArgs = teacher ? new Object[]{id} : new Object[]{id,user.getId()};
        Map<Long,List<FileInfo>> files = new HashMap<>();
        jdbc.query("""
                select f.id,f.name,octet_length(f.data),f.submission_id from homework_file f
                join homework_submission hs on hs.id=f.submission_id where hs.homework_id=?
                """ + studentFilter + " order by f.id", (org.springframework.jdbc.core.RowCallbackHandler) r ->
                files.computeIfAbsent(r.getLong(4), k -> new ArrayList<>()).add(new FileInfo(r.getLong(1),r.getString(2),r.getLong(3))), answerArgs);
        Map<Long,List<Answer>> answers = new HashMap<>();
        jdbc.query("select hs.* from homework_submission hs where hs.homework_id=?" + studentFilter + " order by hs.version desc",
                (org.springframework.jdbc.core.RowCallbackHandler) r -> {
                    long submissionId = r.getLong("id");
                    answers.computeIfAbsent(r.getLong("student_id"), k -> new ArrayList<>()).add(new Answer(submissionId,
                            r.getInt("version"),r.getString("text_answer"),r.getString("status"),r.getString("teacher_comment"),
                            localTime(r.getObject("submitted_at",OffsetDateTime.class)),localTime(r.getObject("checked_at",OffsetDateTime.class)),
                            List.copyOf(files.getOrDefault(submissionId,List.of()))));
                }, answerArgs);
        Object[] audienceArgs = teacher ? new Object[]{id} : new Object[]{id,user.getId()};
        Card card = cards.getFirst();
        String mode = card.mode();
        List<StudentWork> students = jdbc.query("""
                select u.id,u.full_name,p.student_id as paper_student from homework_audience a
                join users u on u.id=a.student_id
                left join homework_paper_mark p on p.homework_id=a.homework_id and p.student_id=a.student_id
                where a.homework_id=?
                """ + (teacher ? "" : " and u.id=?") + " order by u.full_name,u.id", (r,n) -> {
                    long studentId = r.getLong(1);
                    List<Answer> history = answers.getOrDefault(studentId,List.of());
                    Answer latest = history.isEmpty() ? null : history.getFirst();
                    String status = "NOTEBOOK".equals(mode) ?
                            (r.getObject("paper_student") == null ? "NOT_SUBMITTED" : "ACCEPTED") :
                            latest == null ? "NOT_SUBMITTED" : latest.status();
                    return new StudentWork(studentId,r.getString(2),status,latest);
                }, audienceArgs);
        if (!teacher && !students.isEmpty()) card = withStatus(card,students.getFirst().status());
        return new Detail(card,teacher,teacher ? students : List.of(),teacher ? List.of() : List.copyOf(answers.getOrDefault(user.getId(),List.of())));
    }

    public FileDownload download(Authentication auth, long fileId) {
        User user = member(auth);
        Visibility visibility = visible(user);
        List<Object> args = new ArrayList<>(List.of(fileId)); args.addAll(Arrays.asList(visibility.args()));
        String ownSubmission = user.getRole() == Role.STUDENT ? " and hs.student_id=?" : "";
        if (user.getRole() == Role.STUDENT) args.add(user.getId());
        List<FileDownload> files = jdbc.query("""
                select f.name,f.data from homework_file f join homework_submission hs on hs.id=f.submission_id
                join homework h on h.id=hs.homework_id where f.id=? and
                """ + visibility.sql() + ownSubmission, (r,n) -> new FileDownload(r.getString(1),r.getBytes(2)),args.toArray());
        if (files.isEmpty()) throw new AccessDeniedException("Файл недоступен.");
        return files.getFirst();
    }

    private OffsetDateTime localTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toOffsetDateTime();
    }

    /** Для повторного запроса создания передавайте прежний requestToken в перегрузку ниже. */
    @Transactional
    public long create(Authentication auth, long classId, long subjectId, String title,
                       String body, LocalDate dueDate, String mode) {
        return create(auth, classId, subjectId, title, body, dueDate, mode, UUID.randomUUID().toString());
    }

    @Transactional
    public long create(Authentication auth, long classId, long subjectId, String title,
                       String body, LocalDate dueDate, String mode, String requestToken) {
        User teacher = teacher(auth);
        lockSchool(teacher);
        assigned(teacher, classId, subjectId);
        title = required(title, 200, "Укажите заголовок ДЗ (до 200 символов).");
        body = required(body, 10000, "Укажите содержание ДЗ (до 10000 символов).");
        requestToken = required(requestToken, 100, "Укажите идентификатор запроса (до 100 символов).");
        if (dueDate == null) throw new ScheduleException("Укажите срок сдачи.");
        if (!"ONLINE".equals(mode) && !"NOTEBOOK".equals(mode))
            throw new ScheduleException("Способ выполнения: ONLINE или NOTEBOOK.");

        List<Homework> existing = jdbc.query("select * from homework where request_token=?",
                (r, n) -> homework(r), requestToken);
        if (!existing.isEmpty()) {
            Homework h = existing.getFirst();
            if (h.schoolId() != teacher.getSchool().getId() || h.teacherId() != teacher.getTeacherId())
                throw new AccessDeniedException("Идентификатор запроса недоступен.");
            if (h.classId() != classId || h.subjectId() != subjectId || !h.title().equals(title)
                    || !h.body().equals(body) || !h.dueDate().equals(dueDate) || !h.mode().equals(mode))
                throw new ScheduleException("Этот запрос уже использован для другого задания.");
            return h.id();
        }
        var keys = new GeneratedKeyHolder();
        final String savedTitle = title, savedBody = body, token = requestToken;
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into homework(school_id,teacher_id,class_id,subject_id,title,body,
                                         due_date,mode,published_at,request_token)
                    values(?,?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            statement.setLong(1, teacher.getSchool().getId());
            statement.setLong(2, teacher.getTeacherId());
            statement.setLong(3, classId);
            statement.setLong(4, subjectId);
            statement.setString(5, savedTitle);
            statement.setString(6, savedBody);
            statement.setObject(7, dueDate);
            statement.setString(8, mode);
            statement.setObject(9, OffsetDateTime.now(clock));
            statement.setString(10, token);
            return statement;
        }, keys);
        long id = Objects.requireNonNull(keys.getKey(), "Не получен идентификатор ДЗ").longValue();
        jdbc.update("""
                insert into homework_audience(homework_id,student_id)
                select ?,u.id from users u
                where u.school_id=? and u.student_class_id=? and u.role='STUDENT'
                """, id, teacher.getSchool().getId(), classId);
        return id;
    }

    /** Возвращает идентификатор новой версии ответа. Срок сдачи не ограничивает отправку. */
    @Transactional
    public long submit(Authentication auth, long homeworkId, String text, MultipartFile[] files) {
        User student = access.currentUser(auth);
        if (student.getRole() != Role.STUDENT || student.getSchool() == null)
            throw new AccessDeniedException("Ответ отправляет только ученик — адресат ДЗ.");
        Homework h = studentHomework(student, homeworkId);
        lockAudience(h, student.getId());
        requireMode(h, "ONLINE");
        text = optional(text, 10000, "Ответ не должен превышать 10000 символов.");
        List<Upload> uploads = uploads(files);
        if (text.isEmpty() && uploads.isEmpty())
            throw new ScheduleException("Добавьте текст ответа или хотя бы один файл.");
        long previous = jdbc.queryForObject("""
                select coalesce(max(version),0) from homework_submission
                where homework_id=? and student_id=?
                """, Long.class, homeworkId, student.getId());
        if (previous >= Integer.MAX_VALUE) throw new ScheduleException("Достигнут предел числа версий ответа.");
        int version = (int) previous + 1;
        var keys = new GeneratedKeyHolder();
        final String answer = text;
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into homework_submission(homework_id,student_id,version,text_answer,status,submitted_at)
                    values(?,?,?,?,'SENT',?)
                    """, new String[]{"id"});
            statement.setLong(1, homeworkId);
            statement.setLong(2, student.getId());
            statement.setInt(3, version);
            statement.setString(4, answer);
            statement.setObject(5, OffsetDateTime.now(clock));
            return statement;
        }, keys);
        long submissionId = Objects.requireNonNull(keys.getKey(), "Не получен идентификатор ответа").longValue();
        for (Upload upload : uploads)
            jdbc.update("insert into homework_file(submission_id,name,content_type,data) values(?,?,?,?)",
                    submissionId, upload.name(), upload.contentType(), upload.data());
        return submissionId;
    }

    /** Проверяет последнюю версию на момент получения блокировки. */
    @Transactional
    public void check(Authentication auth, long homeworkId, long studentId, String status, String comment) {
        checkLatest(auth, homeworkId, studentId, null, status, comment);
    }

    /** Не позволяет проверить новую отправку вместо версии, которую учитель открыл ранее. */
    @Transactional
    public void check(Authentication auth, long homeworkId, long studentId, int expectedVersion,
                      String status, String comment) {
        checkLatest(auth, homeworkId, studentId, expectedVersion, status, comment);
    }

    private void checkLatest(Authentication auth, long homeworkId, long studentId,
                             Integer expectedVersion, String status, String comment) {
        User teacher = teacher(auth);
        lockSchool(teacher);
        Homework h = teacherHomework(teacher, homeworkId);
        lockAudience(h, studentId);
        requireMode(h, "ONLINE");
        if (!"ACCEPTED".equals(status) && !"RETURNED".equals(status))
            throw new ScheduleException("Результат проверки: ACCEPTED или RETURNED.");
        comment = optional(comment, 2000, "Комментарий не должен превышать 2000 символов.");
        if ("RETURNED".equals(status) && comment.isEmpty())
            throw new ScheduleException("Укажите комментарий для доработки.");
        List<Submission> rows = jdbc.query("""
                select id,version,status,teacher_comment from homework_submission
                where homework_id=? and student_id=? order by version desc limit 1
                """, (r, n) -> new Submission(r.getLong(1), r.getInt(2), r.getString(3), r.getString(4)),
                homeworkId, studentId);
        if (rows.isEmpty()) throw new ScheduleException("Ученик ещё не отправил ответ.");
        Submission last = rows.getFirst();
        if (expectedVersion != null && last.version() != expectedVersion)
            throw new ScheduleException("Ученик отправил новую версию. Обновите ответ перед проверкой.");
        if (last.status().equals(status) && Objects.equals(last.comment(), comment)) return;
        jdbc.update("update homework_submission set status=?,teacher_comment=?,checked_at=?,checked_by=? where id=?",
                status, comment, OffsetDateTime.now(clock), teacher.getId(), last.id());
    }

    @Transactional
    public void markPaper(Authentication auth, long homeworkId, long studentId) {
        User teacher = teacher(auth);
        lockSchool(teacher);
        Homework h = teacherHomework(teacher, homeworkId);
        lockAudience(h, studentId);
        requireMode(h, "NOTEBOOK");
        if (jdbc.queryForObject("select count(*) from homework_paper_mark where homework_id=? and student_id=?",
                Long.class, homeworkId, studentId) == 0)
            jdbc.update("insert into homework_paper_mark(homework_id,student_id,marked_by,marked_at) values(?,?,?,?)",
                    homeworkId, studentId, teacher.getId(), OffsetDateTime.now(clock));
    }

    private User teacher(Authentication auth) {
        User user = access.currentUser(auth);
        if (user.getRole() != Role.TEACHER || user.getSchool() == null || user.getTeacherId() == null)
            throw new AccessDeniedException("ДЗ создаёт и проверяет назначенный учитель.");
        return user;
    }

    private void lockSchool(User user) {
        jdbc.queryForList("select id from schedule_school where id=? for update", user.getSchool().getId());
    }

    private void assigned(User user, long classId, long subjectId) {
        if (jdbc.queryForObject("""
                select count(*) from teaching_assignment a
                where a.school_id=? and a.teacher_id=? and a.class_id=? and a.subject_id=?
                """, Long.class, user.getSchool().getId(), user.getTeacherId(), classId, subjectId) == 0)
            throw new AccessDeniedException("Класс и предмет не назначены этому учителю.");
    }

    private Homework teacherHomework(User teacher, long id) {
        List<Homework> rows = jdbc.query("select * from homework where id=? and school_id=? and teacher_id=?",
                (r, n) -> homework(r), id, teacher.getSchool().getId(), teacher.getTeacherId());
        if (rows.isEmpty()) throw new AccessDeniedException("ДЗ недоступно.");
        Homework h = rows.getFirst();
        assigned(teacher, h.classId(), h.subjectId());
        return h;
    }

    private Homework studentHomework(User student, long id) {
        List<Homework> rows = jdbc.query("""
                select h.* from homework h join homework_audience a on a.homework_id=h.id
                where h.id=? and h.school_id=? and a.student_id=?
                """, (r, n) -> homework(r), id, student.getSchool().getId(), student.getId());
        if (rows.isEmpty()) throw new AccessDeniedException("Вы не являетесь адресатом этого ДЗ.");
        return rows.getFirst();
    }

    private static Homework homework(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Homework(r.getLong("id"), r.getLong("school_id"), r.getLong("teacher_id"),
                r.getLong("class_id"), r.getLong("subject_id"), r.getString("title"), r.getString("body"),
                r.getObject("due_date", LocalDate.class), r.getString("mode"));
    }

    // Одна блокировка на пару ДЗ/ученик сериализует отправку, проверку и бумажную отметку.
    private void lockAudience(Homework h, long studentId) {
        List<Long> rows = jdbc.queryForList("""
                select a.student_id from homework_audience a
                where a.homework_id=? and a.student_id=? and exists
                  (select 1 from users u where u.id=a.student_id and u.school_id=? and u.role='STUDENT')
                for update
                """, Long.class, h.id(), studentId, h.schoolId());
        if (rows.isEmpty()) throw new AccessDeniedException("Ученик не является адресатом этого ДЗ.");
    }

    private static void requireMode(Homework h, String mode) {
        if (!mode.equals(h.mode())) throw new ScheduleException("Действие не соответствует способу выполнения ДЗ.");
    }

    private static String required(String value, int max, String message) {
        String result = optional(value, max, message);
        if (result.isEmpty()) throw new ScheduleException(message);
        return result;
    }

    private static String optional(String value, int max, String message) {
        if (value == null) return "";
        if (value.length() > max) throw new ScheduleException(message);
        return value.strip();
    }

    private static List<Upload> uploads(MultipartFile[] files) {
        List<Upload> result = new ArrayList<>();
        if (files == null) return result;
        long count = Arrays.stream(files).filter(f -> f != null && !f.isEmpty()).count();
        if (count > MAX_FILES) throw new ScheduleException("Можно приложить не более пяти файлов.");
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            if (file.getSize() > MAX_FILE_BYTES) throw new ScheduleException("Файл превышает 10 МБ.");
            byte[] data;
            try (var input = file.getInputStream()) {
                data = input.readNBytes(MAX_FILE_BYTES + 1);
            } catch (IOException ex) {
                throw new ScheduleException("Не удалось прочитать файл.");
            }
            if (data.length == 0) throw new ScheduleException("Нельзя приложить пустой файл.");
            if (data.length > MAX_FILE_BYTES) throw new ScheduleException("Файл превышает 10 МБ.");
            String name = Objects.requireNonNullElse(file.getOriginalFilename(), "file").replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
            if (name.length() > 200) name = name.substring(name.length() - 200);
            String type = contentType(name, data);
            result.add(new Upload(name, type, data));
        }
        return result;
    }

    /** MIME браузера не используется: расширение должно соответствовать сигнатуре содержимого. */
    private static String contentType(String name, byte[] data) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf") && startsWith(data, "%PDF-".getBytes(StandardCharsets.US_ASCII)))
            return "application/pdf";
        if ((lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
                && startsWith(data, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}))
            return "image/jpeg";
        if (lower.endsWith(".png") && startsWith(data,
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}))
            return "image/png";
        if (lower.endsWith(".docx") && startsWith(data, new byte[]{0x50, 0x4b, 0x03, 0x04}) && validDocx(data))
            return DOCX_TYPE;
        throw new ScheduleException("Разрешены JPG, PNG, PDF и DOCX с корректным содержимым.");
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }

    private static boolean validDocx(byte[] data) {
        boolean document = false, types = false;
        int entries = 0;
        long expanded = 0;
        byte[] buffer = new byte[8192];
        try (var zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_DOCX_ENTRIES)
                    throw new ScheduleException("Слишком много записей в DOCX.");
                long size = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expanded += read;
                    size += read;
                    if (expanded > MAX_DOCX_EXPANDED_BYTES)
                        throw new ScheduleException("Слишком большой распакованный DOCX.");
                }
                if (!entry.isDirectory() && size > 0) {
                    if ("word/document.xml".equals(entry.getName())) document = true;
                    if ("[Content_Types].xml".equals(entry.getName())) types = true;
                }
            }
        } catch (IOException ex) {
            throw new ScheduleException("Повреждённый DOCX.");
        }
        return document && types;
    }
}
