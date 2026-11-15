package com.example.edujurnal;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.entity.User;
import com.example.edujurnal.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class CreateOwnerUser implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public CreateOwnerUser(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        // Проверяем, существует ли owner
        if (userRepository.findByLogin("owner").isPresent()) {
            System.out.println("Owner already exists, skipping creation.");
            return;
        }

        // Создаем owner
        User owner = new User();
        owner.setLogin("owner");
        owner.setPassword(passwordEncoder.encode("owner123"));
        owner.setRole(Role.OWNER);
        owner.setEnabled(true);
        owner.setFullName("Owner System");
        owner.setSchool(null);

        userRepository.save(owner);
        System.out.println("====================================");
        System.out.println("Owner user created successfully!");
        System.out.println("Login: owner");
        System.out.println("Password: owner123");
        System.out.println("====================================");
    }
}
