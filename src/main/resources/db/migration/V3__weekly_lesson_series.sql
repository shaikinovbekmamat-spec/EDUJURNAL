alter table schedule_lesson add column series_id varchar(36);
create index schedule_lesson_series on schedule_lesson(school_id, series_id, lesson_date);
