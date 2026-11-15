package com.example.edujurnal.repository;

import com.example.edujurnal.entity.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LessonRepository extends JpaRepository<Lesson, Long> {
    @Query("""
            select l from Lesson l where l.schoolId = :schoolId and l.date between :start and :end
            and (:classId is null or l.classId = :classId)
            and (:teacherId is null or l.teacherId = :teacherId)
            order by l.date, l.startTime, l.id
            """)
    List<Lesson> findVisibleForTerm(@Param("schoolId") long schoolId, @Param("start") LocalDate start,
                                  @Param("end") LocalDate end, @Param("classId") Long classId,
                                  @Param("teacherId") Long teacherId);
    List<Lesson> findBySchoolIdAndDateOrderByStartTimeAscIdAsc(long schoolId, LocalDate date);
    Optional<Lesson> findByIdAndSchoolId(long id, long schoolId);
    Optional<Lesson> findByRequestTokenAndSchoolId(String token, long schoolId);
    List<Lesson> findBySchoolIdAndDateBetweenOrderByDateAscStartTimeAscIdAsc(
            long schoolId, LocalDate from, LocalDate to);
    List<Lesson> findBySchoolIdAndSeriesIdOrderByDateAscIdAsc(long schoolId, String seriesId);
}
