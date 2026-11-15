package com.example.edujurnal.repository;

import com.example.edujurnal.entity.School;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SchoolRepository extends JpaRepository<School, Long> {
    List<School> findAllByOrderByNameAsc();
}
