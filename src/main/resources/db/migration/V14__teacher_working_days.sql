-- Availability is now selected by whole working days. Keep fully blocked days;
-- release former single-lesson restrictions, which the new editor cannot represent.
delete from schedule_teacher_unavailable
where (school_id, teacher_id, weekday) in (
    select school_id, teacher_id, weekday
    from schedule_teacher_unavailable
    group by school_id, teacher_id, weekday
    having count(*) < 6
);
