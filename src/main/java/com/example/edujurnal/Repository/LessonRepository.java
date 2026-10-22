package com.example.edujurnal.Repository;

import com.example.edujurnal.Entity.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LessonRepository extends JpaRepository<Lesson, Long> {
    List<Lesson> findBySchoolIdAndDateOrderByStartTimeAscIdAsc(long schoolId, LocalDate date);
    Optional<Lesson> findByIdAndSchoolId(long id, long schoolId);
    Optional<Lesson> findByRequestTokenAndSchoolId(String token, long schoolId);
    List<Lesson> findBySchoolIdAndDateBetweenOrderByDateAscStartTimeAscIdAsc(
            long schoolId, LocalDate from, LocalDate to);
    List<Lesson> findBySchoolIdAndSeriesIdOrderByDateAscIdAsc(long schoolId, String seriesId);
}
