package com.example.edujurnal.academic;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Opt-in integration check: creates and removes only its own unique schema. */
@EnabledIfEnvironmentVariable(named="EDUJOURNAL_VERIFY_POSTGRES",matches="true")
class PostgresGradeTests {
    @Test void migrationsAndImmutableGradeTriggersWorkOnPostgres() throws Exception {
        String url=System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/tkrpo");
        String user=System.getenv().getOrDefault("DB_USER","postgres");
        String password=System.getenv("DB_PASSWORD");
        String schema="academic_check_"+UUID.randomUUID().toString().replace("-","");
        try(Connection connection=DriverManager.getConnection(url,user,password);Statement sql=connection.createStatement()) {
            sql.execute("create schema "+schema);
            try {
                Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
                sql.execute("set search_path to "+schema);
                sql.execute("insert into schedule_school values(1,'Test school')");
                sql.execute("insert into schedule_class(id,school_id,name) values(1,1,'7A')");
                sql.execute("insert into schedule_subject values(1,1,'Math')");
                sql.execute("insert into schedule_teacher values(1,1,'Teacher')");
                sql.execute("insert into schedule_room(id,school_id,name) values(1,1,'101')");
                sql.execute("insert into schedule_teacher_subject values(1,1,1)");
                sql.execute("insert into users(id,login,password,role,school_id) values(1,'1','test-only','STUDENT',1),(2,'2','test-only','TEACHER',1)");
                sql.execute("insert into academic_year(id,school_id,name,starts_on,ends_on) values(1,1,'2030','2030-09-01','2031-08-31')");
                sql.execute("insert into academic_quarter(id,year_id,number,starts_on,ends_on) values(1,1,1,'2030-09-01','2030-10-31')");
                sql.execute("insert into schedule_lesson(id,school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token) values(1,1,1,1,1,1,'2030-10-21','10:00','10:45','test')");
                sql.execute("insert into journal_grade(lesson_id,student_id,teacher_user_id,quarter_id,grade_value) values(1,1,2,1,5)");
                sql.execute("insert into journal_final_grade(year_id,period_number,school_id,class_id,subject_id,student_id,teacher_user_id,grade_value) values(1,0,1,1,1,1,2,4)");
                assertThatThrownBy(()->sql.execute("update journal_final_grade set grade_value=5")).isInstanceOf(SQLException.class).hasMessageContaining("cannot be changed");
                assertThatThrownBy(()->sql.execute("delete from journal_final_grade")).isInstanceOf(SQLException.class).hasMessageContaining("cannot be changed");
                assertThatThrownBy(()->sql.execute("update journal_grade set grade_value=4")).isInstanceOf(SQLException.class).hasMessageContaining("cannot be changed");
                assertThatThrownBy(()->sql.execute("delete from journal_grade")).isInstanceOf(SQLException.class).hasMessageContaining("cannot be changed");
                assertThatThrownBy(()->sql.execute("update schedule_lesson set lesson_date='2030-10-22' where id=1")).isInstanceOf(SQLException.class).hasMessageContaining("cannot be changed");
                try(var result=sql.executeQuery("select grade_value from journal_grade")) {assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isEqualTo(5);}
            } finally {
                sql.execute("set search_path to public");
                sql.execute("drop schema "+schema+" cascade");
            }
        }
    }
}
