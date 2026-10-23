package com.example.edujurnal.Service;

import com.example.edujurnal.Entity.Role;
import com.example.edujurnal.Entity.School;
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

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        // Изменили findByPhoneNumber на findByLogin
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
        user.setLogin(login); // было setPhoneNumber
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
}