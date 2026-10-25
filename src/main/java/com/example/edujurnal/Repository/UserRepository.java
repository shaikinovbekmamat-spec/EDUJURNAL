package com.example.edujurnal.repository;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = "school")
    Optional<User> findByLogin(String login);

    List<User> findAllBySchoolIdAndRole(Long schoolId, Role role);
    boolean existsByIdAndSchoolId(Long id, Long schoolId);
}
