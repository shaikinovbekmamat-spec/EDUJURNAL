create table schedule_curriculum (
    school_id bigint not null references schedule_school(id),
    class_id bigint not null,
    subject_id bigint not null,
    teacher_id bigint,
    room_id bigint,
    weekly_count integer not null check (weekly_count between 1 and 30),
    primary key (school_id, class_id, subject_id),
    foreign key (school_id, class_id) references schedule_class(school_id, id) on delete cascade,
    foreign key (school_id, subject_id) references schedule_subject(school_id, id) on delete cascade,
    foreign key (school_id, teacher_id) references schedule_teacher(school_id, id),
    foreign key (school_id, room_id) references schedule_room(school_id, id)
);
create table schedule_planning_settings (
    school_id bigint primary key references schedule_school(id),
    daily_limit integer not null check (daily_limit between 1 and 6),
    days_per_week integer not null check (days_per_week between 1 and 5)
);
create table schedule_teacher_unavailable (
    school_id bigint not null,
    teacher_id bigint not null,
    weekday integer not null check (weekday between 1 and 5),
    lesson_number integer not null check (lesson_number between 1 and 6),
    primary key (school_id, teacher_id, weekday, lesson_number),
    foreign key (school_id, teacher_id) references schedule_teacher(school_id, id) on delete cascade
);
