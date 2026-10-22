package com.example.edujurnal.Service;

import com.example.edujurnal.Entity.Role;
import com.example.edujurnal.Entity.User;
import com.example.edujurnal.Repository.UserRepository;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService implements UserDetailsService {

    private static final int MAX_FAILED_ATTEMPTS = 3;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserDetails loadUserByUsername(String phoneNumber) throws UsernameNotFoundException {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Пользователь с номером " + phoneNumber + " не найден"));

        if (!user.isEnabled()) {
            throw new DisabledException("Аккаунт заблокирован после превышения лимита попыток входа");
        }

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getPhoneNumber())
                .password(user.getPassword())
                .authorities("ROLE_" + user.getRole().name())
                .build();
    }

    @Transactional
    public void increaseFailedAttempts(String phoneNumber) {
        userRepository.findByPhoneNumber(phoneNumber).ifPresent(user -> {
            int newFailAttempts = user.getFailedAttempts() + 1;
            user.setFailedAttempts(newFailAttempts);

            if (newFailAttempts >= MAX_FAILED_ATTEMPTS) {
                user.setEnabled(false);
            }

            userRepository.save(user);
        });
    }

    @Transactional
    public void resetFailedAttempts(String phoneNumber) {
        userRepository.findByPhoneNumber(phoneNumber).ifPresent(user -> {
            user.setFailedAttempts(0);
            userRepository.save(user);
        });
    }

    @Transactional
    public User createUser(String phoneNumber, String rawPassword, Role role, String fullName) {
        User user = new User();
        user.setPhoneNumber(phoneNumber);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setFullName(fullName);
        user.setEnabled(true);
        user.setFailedAttempts(0);
        return userRepository.save(user);
    }

    public User findByPhoneNumber(String phoneNumber) {
        return userRepository.findByPhoneNumber(phoneNumber).orElse(null);
    }
}