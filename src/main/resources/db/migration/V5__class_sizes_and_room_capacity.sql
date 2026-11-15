alter table schedule_class add column student_count integer not null default 0 check (student_count >= 0);
alter table schedule_room add column capacity integer not null default 36 check (capacity > 0);
