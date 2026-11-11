package com.example.edujurnal.academic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

class AccountSequenceMigrationTests {
    @Test void repairsImportedIdsOnH2AndPreservesAdvancedCounter() throws Exception {
        for (boolean advanced : new boolean[]{false,true}) {
            try (var source = new HikariDataSource()) {
                source.setJdbcUrl("jdbc:h2:mem:account-sequence-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
                source.setUsername("sa"); source.setPassword("");
                Flyway.configure().dataSource(source).target("20").load().migrate();
                try (Connection connection = source.getConnection(); var sql = connection.createStatement()) {
                    seed(connection);
                    sql.execute("alter table users alter column id restart with " + (advanced ? 5000 : 1));
                    Flyway.configure().dataSource(source).load().migrate();
                    assertThat(insertTeacher(connection,"101")).isEqualTo(advanced ? 5000 : 17);
                }
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="EDUJOURNAL_VERIFY_POSTGRES",matches="true")
    void reproducesPrimaryKeyCollisionAndRepairsItOnPostgres() throws Exception {
        verifyPostgres(false);
        verifyPostgres(true);
    }

    private void verifyPostgres(boolean advanced) throws Exception {
        String url = System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/tkrpo");
        String username = System.getenv().getOrDefault("DB_USER","postgres");
        String password = System.getenv("DB_PASSWORD");
        String schema = "account_sequence_check_" + UUID.randomUUID().toString().replace("-","");
        try (Connection connection = DriverManager.getConnection(url,username,password); var sql = connection.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema).target("20").load().migrate();
                sql.execute("set search_path to " + schema);
                seed(connection);
                if (advanced) sql.execute("select setval(pg_get_serial_sequence('users','id'),5000,false)");
                else assertThatThrownBy(() -> insertTeacher(connection,"100"))
                        .isInstanceOf(SQLException.class).satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo("23505"));
                Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema).load().migrate();
                assertThat(insertTeacher(connection,"101")).isEqualTo(advanced ? 5000 : 17);
                try (var result = sql.executeQuery("select count(*) from users where id in (1,10,16)")) {
                    result.next(); assertThat(result.getInt(1)).isEqualTo(3);
                }
            } finally {
                sql.execute("set search_path to public");
                sql.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private void seed(Connection connection) throws Exception {
        try (var sql = connection.createStatement()) {
            sql.execute("insert into schedule_school values(1,'Test school')");
            sql.execute("insert into users(id,login,password,role,school_id) values"
                    + "(1,'1','test-only','DIRECTOR',1),(10,'10','test-only','STUDENT',1),(16,'16','test-only','PARENT',1)");
        }
    }

    private long insertTeacher(Connection connection, String login) throws Exception {
        try (var statement = connection.prepareStatement("insert into users(login,password,role,school_id) values(?,'test-only','TEACHER',1)",new String[]{"id"})) {
            statement.setString(1,login); statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) { assertThat(keys.next()).isTrue(); return keys.getLong(1); }
        }
    }
}
