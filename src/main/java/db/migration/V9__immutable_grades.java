package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** PostgreSQL also enforces immutability for writes outside the application. */
public class V9__immutable_grades extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        if (!context.getConnection().getMetaData().getDatabaseProductName().equals("PostgreSQL")) return;
        try(var statement=context.getConnection().createStatement()) {
            statement.execute("""
                create function reject_grade_change() returns trigger language plpgsql as $$
                begin
                    raise exception 'Saved grades cannot be changed or deleted';
                end; $$
                """);
            statement.execute("create trigger journal_grade_immutable before update or delete on journal_grade for each row execute function reject_grade_change()");
            statement.execute("""
                create function reject_graded_lesson_change() returns trigger language plpgsql as $$
                begin
                    if exists(select 1 from journal_grade where lesson_id=old.id) then
                        raise exception 'A lesson with saved grades cannot be changed';
                    end if;
                    return new;
                end; $$
                """);
            statement.execute("create trigger graded_lesson_immutable before update on schedule_lesson for each row execute function reject_graded_lesson_change()");
        }
    }
}
