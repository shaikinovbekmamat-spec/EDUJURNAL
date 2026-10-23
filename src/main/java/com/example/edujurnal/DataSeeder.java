// package com.example.edujurnal.auth;
//
//    import com.example.edujurnal.Entity.Role;
//    import com.example.edujurnal.Entity.User;
//    import com.example.edujurnal.Repository.UserRepository;
//    import org.springframework.boot.CommandLineRunner;
//    import org.springframework.context.annotation.Profile;
//    import org.springframework.security.crypto.password.PasswordEncoder;
//    import org.springframework.stereotype.Component;
//
//    @Component
//    @Profile("demo")
//    public class DataSeeder implements CommandLineRunner {
//
//        private final UserRepository userRepository;
//        private final PasswordEncoder passwordEncoder;
//
//        public DataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
//            this.userRepository = userRepository;
//            this.passwordEncoder = passwordEncoder;
//        }
//
//        @Override
//        public void run(String... args) {
//            createUserIfNotExists("0555123456", "password123", Role.ADMIN, "Директор Иванов");
//            createUserIfNotExists("0555234567", "password123", Role.TEACHER, "Учитель Петрова");
//            createUserIfNotExists("0555345678", "password123", Role.STUDENT, "Ученик Сидоров");
//            System.out.println("✅ Тестовые пользователи проверены/созданы.");
//        }
//
//        private void createUserIfNotExists(String phone, String rawPassword, Role role, String fullName) {
//            if (userRepository.findByPhoneNumber(phone).isEmpty()) {
//                User user = new User();
//                user.setPhoneNumber(phone);
//                user.setPassword(passwordEncoder.encode(rawPassword));
//                user.setRole(role);
//                user.setEnabled(true);
//                user.setFailedAttempts(0);
//                user.setFullName(fullName);
//                userRepository.save(user);
//            }
//        }
//    }