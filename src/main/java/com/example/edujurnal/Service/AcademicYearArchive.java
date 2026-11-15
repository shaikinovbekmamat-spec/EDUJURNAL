package com.example.edujurnal.service;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.exception.ScheduleException;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcademicYearArchive {
    private final JdbcTemplate jdbc;
    private final ScheduleAccessService access;
    private final Clock clock;

    public AcademicYearArchive(JdbcTemplate jdbc, ScheduleAccessService access, Clock clock) {
        this.jdbc=jdbc; this.access=access; this.clock=clock;
    }

    @Transactional
    public void archive(Authentication auth, long yearId) {
        var actor=access.currentUser(auth);
        if(actor.getRole()!=Role.DIRECTOR || actor.getSchool()==null)
            throw new AccessDeniedException("Учебный год архивирует директор своей школы.");
        long school=actor.getSchool().getId();
        // Same lock as all writes: an in-flight edit finishes before archiving.
        jdbc.queryForList("select id from schedule_school where id=? for update",school);
        var rows=jdbc.queryForList("select * from academic_year where id=? and school_id=?",yearId,school);
        if(rows.isEmpty()) throw new AccessDeniedException("Учебный год недоступен.");
        var year=rows.getFirst();
        if(Boolean.TRUE.equals(year.get("archived"))) return;
        if(!((java.sql.Date)year.get("ends_on")).toLocalDate().isBefore(LocalDate.now(clock)))
            throw new ScheduleException("Архивировать можно только завершённый учебный год, после даты его окончания.");
        jdbc.update("update academic_year set archived=true,archived_at=?,archived_by=? where id=?",
                OffsetDateTime.now(clock),actor.getId(),yearId);
    }

    // Call from a write transaction while holding the school lock.
    public static void requireOpenYear(JdbcTemplate jdbc,long yearId) {
        if(jdbc.queryForObject("select count(*) from academic_year where id=? and archived=true",Long.class,yearId)>0)
            throw new ScheduleException("Учебный год в архиве. Доступен только просмотр.");
    }

    public static void requireOpenDate(JdbcTemplate jdbc,long school,LocalDate date) {
        if(jdbc.queryForObject("select count(*) from academic_year where school_id=? and archived=true and ? between starts_on and ends_on",Long.class,school,date)>0)
            throw new ScheduleException("Учебный год в архиве. Доступен только просмотр.");
    }

    public static void requireOpenHomework(JdbcTemplate jdbc,long school,long id) {
        LocalDate published=jdbc.queryForObject("select published_at from homework where id=?",(r,n)->
                r.getObject(1,OffsetDateTime.class).atZoneSameInstant(ZoneId.of("Asia/Bishkek")).toLocalDate(),id);
        requireOpenDate(jdbc,school,published);
    }
}
