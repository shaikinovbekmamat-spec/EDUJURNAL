alter table academic_year add column archived boolean not null default false;
alter table academic_year add column archived_at timestamp with time zone;
alter table academic_year add column archived_by bigint references users(id);
alter table academic_year add constraint academic_year_archive_state check (
    (archived = false and archived_at is null and archived_by is null)
    or (archived = true and archived_at is not null and archived_by is not null)
);
