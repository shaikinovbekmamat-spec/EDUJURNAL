package com.example.edujurnal.Repository;

import com.example.edujurnal.Entity.Role;
import com.example.edujurnal.Entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // Было findByPhoneNumber
    Optional<User> findByLogin(String login);

    List<User> findAllBySchoolIdAndRole(Long schoolId, Role role);
    boolean existsByIdAndSchoolId(Long id, Long schoolId);
}