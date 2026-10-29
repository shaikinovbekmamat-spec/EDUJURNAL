package com.example.edujurnal.service;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.entity.School;
import com.example.edujurnal.entity.User;
import com.example.edujurnal.repository.UserRepository;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        User user = userRepository.findByLogin(login)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));

        if (!user.isEnabled()) {
            throw new DisabledException("Аккаунт заблокирован администратором");
        }

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getLogin())
                .password(user.getPassword())
                .authorities("ROLE_" + user.getRole().name())
                .build();
    }

    @Transactional
    public User createUser(String login, String rawPassword, Role role, String fullName, School school) {
        User user = new User();
        user.setLogin(login);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setFullName(fullName);
        user.setSchool(school);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    public User findByLogin(String login) {
        return userRepository.findByLogin(login).orElse(null);
    }

    @Transactional
    public void changePassword(String login, String currentPassword, String newPassword, String confirmation) {
        User user = userRepository.findByLogin(login)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        if (!user.isEnabled()) throw new DisabledException("Аккаунт заблокирован");
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPassword()))
            throw new IllegalArgumentException("Текущий пароль указан неверно.");
        if (newPassword == null || newPassword.length() < 8 ||
                newPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Пароль должен содержать минимум 8 символов и не более 72 байт UTF-8.");
        if (!newPassword.equals(confirmation))
            throw new IllegalArgumentException("Подтверждение пароля не совпадает.");
        if (passwordEncoder.matches(newPassword, user.getPassword()))
            throw new IllegalArgumentException("Новый пароль должен отличаться от текущего.");
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}
