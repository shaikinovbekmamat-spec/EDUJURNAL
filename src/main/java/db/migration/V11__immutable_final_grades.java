package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V11__immutable_final_grades extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        if (!context.getConnection().getMetaData().getDatabaseProductName().equals("PostgreSQL")) return;
        try(var statement=context.getConnection().createStatement()) {
            statement.execute("create trigger journal_final_grade_immutable before update or delete on journal_final_grade for each row execute function reject_grade_change()");
        }
    }
}
