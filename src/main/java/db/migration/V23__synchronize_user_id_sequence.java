package db.migration;

import java.sql.Connection;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Repairs identity counters after importing accounts with explicit IDs. Never rewinds a counter. */
public class V23__synchronize_user_id_sequence extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String database = connection.getMetaData().getDatabaseProductName();
        if ("PostgreSQL".equals(database)) repairPostgres(connection);
        else if ("H2".equals(database)) repairH2(connection);
    }

    private static long maxId(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("select coalesce(max(id),0) from users")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void repairPostgres(Connection connection) throws Exception {
        // INSERT obtains a conflicting table lock before evaluating the identity default.
        try (var statement = connection.createStatement()) {
            statement.execute("lock table users in share row exclusive mode");
        }
        long maximum = maxId(connection);
        String sequence;
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("select pg_get_serial_sequence('users','id')")) {
            result.next();
            sequence = result.getString(1);
        }
        if (sequence == null) throw new IllegalStateException("users.id has no identity sequence");
        // pg_get_serial_sequence returns a server-quoted qualified identifier, never user input.
        long last;
        boolean called;
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("select last_value,is_called from " + sequence)) {
            result.next();
            last = result.getLong(1);
            called = result.getBoolean(2);
        }
        if (maximum > last || (maximum == last && !called)) {
            try (var statement = connection.prepareStatement("select setval(cast(? as regclass),?,true)")) {
                statement.setString(1, sequence);
                statement.setLong(2, maximum);
                statement.execute();
            }
        }
    }

    private static void repairH2(Connection connection) throws Exception {
        long maximum = maxId(connection);
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     select identity_base from information_schema.columns
                     where table_schema=current_schema() and table_name='USERS' and column_name='ID'
                     """)) {
            if (result.next() && result.getLong(1) <= maximum) {
                try (var alter = connection.createStatement()) {
                    alter.execute("alter table users alter column id restart with " + Math.addExact(maximum, 1));
                }
            }
        }
    }
}
