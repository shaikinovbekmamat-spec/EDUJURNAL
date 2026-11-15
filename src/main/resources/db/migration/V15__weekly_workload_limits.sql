create table schedule_class_weekly_limit (
    school_id bigint not null,
    class_id bigint not null,
    weekly_limit integer not null check (weekly_limit between 0 and 30),
    primary key (school_id, class_id),
    foreign key (school_id, class_id) references schedule_class(school_id, id) on delete cascade
);
create table schedule_teacher_weekly_limit (
    school_id bigint not null,
    teacher_id bigint not null,
    weekly_limit integer not null check (weekly_limit between 0 and 30),
    primary key (school_id, teacher_id),
    foreign key (school_id, teacher_id) references schedule_teacher(school_id, id) on delete cascade
);
