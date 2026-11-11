\set ON_ERROR_STOP on
\encoding UTF8

-- Adds exactly ten test accounts. Existing accounts are never overwritten.
-- BCryptPasswordEncoder.encode("password123"), verified with PasswordEncoder.matches.
BEGIN;

SELECT id FROM schedule_school WHERE id = 1 FOR UPDATE;
LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF (SELECT count(*) FROM schedule_class
        WHERE school_id = 1 AND ((id = 1 AND name = '10-А') OR (id = 2 AND name = '11-Б'))) <> 2 THEN
        RAISE EXCEPTION 'Expected classes 10-А (1) and 11-Б (2) in school 1';
    END IF;
    IF EXISTS (SELECT 1 FROM users WHERE login IN
        ('stud6','stud7','stud8','stud9','stud10','stud11','stud12','stud13','stud14','stud15')) THEN
        RAISE EXCEPTION 'A requested student login already exists; no accounts were added';
    END IF;
END $$;

INSERT INTO users (login, full_name, password, role, school_id, student_class_id, enabled, failed_attempts)
SELECT login, full_name,
       '$2a$10$HwmOkBUiG5Jev.92Loz4Ke63NCegHRvDzM.kQns8y6kRKLbkXySx6',
       'STUDENT', 1, class_id, true, 0
FROM (VALUES
    ('stud6',  'Иванов Иван Сергеевич',             1),
    ('stud7',  'Петрова Мария Андреевна',           1),
    ('stud8',  'Садыков Азамат Бакытович',          1),
    ('stud9',  'Абдылдаева Айпери Нурлановна',      1),
    ('stud10', 'Ким Александр Викторович',          1),
    ('stud11', 'Смирнов Дмитрий Алексеевич',         2),
    ('stud12', 'Кузнецова Анна Павловна',           2),
    ('stud13', 'Осмонов Нурсултан Эркинович',       2),
    ('stud14', 'Токтогулова Алина Руслановна',       2),
    ('stud15', 'Волкова Екатерина Игоревна',         2)
) AS students(login, full_name, class_id)
RETURNING id, login, full_name, student_class_id;

COMMIT;
