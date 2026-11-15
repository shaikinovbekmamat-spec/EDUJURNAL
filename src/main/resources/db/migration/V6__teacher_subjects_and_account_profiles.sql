create table schedule_teacher_subject (
    school_id bigint not null,
    teacher_id bigint not null,
    subject_id bigint not null,
    primary key (school_id, teacher_id, subject_id),
    foreign key (school_id, teacher_id) references schedule_teacher(school_id, id),
    foreign key (school_id, subject_id) references schedule_subject(school_id, id)
);

-- Preserve existing assignments during the schema upgrade. Future changes are explicit.
insert into schedule_teacher_subject (school_id, teacher_id, subject_id)
select distinct school_id, teacher_id, subject_id from schedule_lesson;

alter table schedule_lesson add constraint fk_lesson_teacher_subject
    foreign key (school_id, teacher_id, subject_id)
    references schedule_teacher_subject(school_id, teacher_id, subject_id);

alter table users add column teacher_id bigint;
alter table users add column student_class_id bigint;
alter table users add constraint fk_user_teacher foreign key (school_id, teacher_id)
    references schedule_teacher(school_id, id);
alter table users add constraint fk_user_student_class foreign key (school_id, student_class_id)
    references schedule_class(school_id, id);
alter table users add constraint chk_user_teacher_role check (teacher_id is null or role = 'TEACHER');
alter table users add constraint chk_user_student_class_role check (student_class_id is null or role = 'STUDENT');
alter table users add constraint uq_user_teacher unique (school_id, teacher_id);
