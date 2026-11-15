-- A class teacher is an assignment of an existing account, not another role.
alter table users add constraint uq_users_school_id unique (school_id, id);

create table class_teacher (
    school_id bigint not null,
    class_id bigint primary key,
    teacher_user_id bigint not null,
    foreign key (school_id, class_id) references schedule_class(school_id, id) on delete cascade,
    foreign key (school_id, teacher_user_id) references users(school_id, id) on delete cascade
);
create index class_teacher_user on class_teacher(teacher_user_id);
