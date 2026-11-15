package com.example.edujurnal.repository;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = "school")
    Optional<User> findByLogin(String login);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.login = :login")
    Optional<User> findByLoginForUpdate(@Param("login") String login);

    List<User> findAllBySchoolIdAndRole(Long schoolId, Role role);
    boolean existsByIdAndSchoolId(Long id, Long schoolId);
}
