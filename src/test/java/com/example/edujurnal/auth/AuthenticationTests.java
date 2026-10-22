package com.example.edujurnal.auth;

import com.example.edujurnal.Entity.Role;
import com.example.edujurnal.Entity.User;
import com.example.edujurnal.Repository.UserRepository;
import com.example.edujurnal.Service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AuthenticationTests {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void testUserCreation() {
        String phoneNumber = "0555999888";
        String password = "testpass";

        User user = userService.createUser(phoneNumber, password, Role.STUDENT, "Тестовый Ученик");

        assertNotNull(user);
        assertEquals(phoneNumber, user.getPhoneNumber());
        assertTrue(passwordEncoder.matches(password, user.getPassword()));
        assertEquals(Role.STUDENT, user.getRole());
        assertTrue(user.isEnabled());
        assertEquals(0, user.getFailedAttempts());

        userRepository.delete(user);
    }

    @Test
    void testFailedAttemptsTracking() {
        String phoneNumber = "0555888777";
        User user = userService.createUser(phoneNumber, "password", Role.TEACHER, "Тест Учитель");

        assertEquals(0, user.getFailedAttempts());
        assertTrue(user.isEnabled());

        userService.increaseFailedAttempts(phoneNumber);
        user = userService.findByPhoneNumber(phoneNumber);
        assertEquals(1, user.getFailedAttempts());
        assertTrue(user.isEnabled());

        userService.increaseFailedAttempts(phoneNumber);
        user = userService.findByPhoneNumber(phoneNumber);
        assertEquals(2, user.getFailedAttempts());
        assertTrue(user.isEnabled());

        userService.increaseFailedAttempts(phoneNumber);
        user = userService.findByPhoneNumber(phoneNumber);
        assertEquals(3, user.getFailedAttempts());
        assertFalse(user.isEnabled(), "Account should be locked after 3 failed attempts");

        userRepository.delete(user);
    }

    @Test
    void testResetFailedAttempts() {
        String phoneNumber = "0555777666";
        User user = userService.createUser(phoneNumber, "password", Role.STUDENT, "Тест Сброс");

        userService.increaseFailedAttempts(phoneNumber);
        userService.increaseFailedAttempts(phoneNumber);

        user = userService.findByPhoneNumber(phoneNumber);
        assertEquals(2, user.getFailedAttempts());

        userService.resetFailedAttempts(phoneNumber);
        user = userService.findByPhoneNumber(phoneNumber);
        assertEquals(0, user.getFailedAttempts());

        userRepository.delete(user);
    }

    @Test
    void testRoleBasedAccess() {
        User admin = userService.createUser("0555111222", "pass", Role.ADMIN, "Админ");
        User teacher = userService.createUser("0555222333", "pass", Role.TEACHER, "Учитель");
        User student = userService.createUser("0555333444", "pass", Role.STUDENT, "Ученик");

        assertEquals(Role.ADMIN, admin.getRole());
        assertEquals(Role.TEACHER, teacher.getRole());
        assertEquals(Role.STUDENT, student.getRole());

        userRepository.deleteAll(java.util.List.of(admin, teacher, student));
    }
}
