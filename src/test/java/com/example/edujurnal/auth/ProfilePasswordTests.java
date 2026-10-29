package com.example.edujurnal.auth;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.entity.User;
import com.example.edujurnal.repository.UserRepository;
import com.example.edujurnal.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfilePasswordTests {
    @Test
    void changesPasswordForEveryRoleAndRejectsInvalidInput() {
        for (Role role : Role.values()) {
            var repository = mock(UserRepository.class);
            var encoder = new BCryptPasswordEncoder(4);
            var service = new UserService(repository, encoder);
            var user = new User();
            user.setRole(role);
            user.setEnabled(true);
            user.setPassword(encoder.encode("old-password"));
            when(repository.findByLogin("self")).thenReturn(Optional.of(user));
            String original = user.getPassword();
            assertThatThrownBy(() -> service.changePassword("self", "wrong", "new-password", "new-password")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.changePassword("self", "old-password", "short", "short")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.changePassword("self", "old-password", "new-password", "different")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.changePassword("self", "old-password", "old-password", "old-password")).isInstanceOf(IllegalArgumentException.class);
            String oversized = "я".repeat(37);
            assertThatThrownBy(() -> service.changePassword("self", "old-password", oversized, oversized)).isInstanceOf(IllegalArgumentException.class);
            assertThat(user.getPassword()).isEqualTo(original);
            verify(repository, never()).save(any());
            service.changePassword("self", "old-password", "new-password", "new-password");
            assertThat(encoder.matches("new-password", user.getPassword())).isTrue();
            assertThat(encoder.matches("old-password", user.getPassword())).isFalse();
            verify(repository).save(user);
        }
    }
}
