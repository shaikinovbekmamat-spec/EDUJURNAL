package com.example.edujurnal.service;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.entity.School;
import com.example.edujurnal.entity.User;
import com.example.edujurnal.repository.SchoolRepository;
import com.example.edujurnal.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ScheduleAccessService {
    private final UserRepository users;
    private final SchoolRepository schools;

    public ScheduleAccessService(UserRepository users, SchoolRepository schools) {
        this.users = users;
        this.schools = schools;
    }

    public User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Войдите в систему.");
        }
        User user = users.findByLogin(authentication.getName())
                .orElseThrow(() -> new AccessDeniedException("Аккаунт не найден."));
        if (!user.isEnabled()) throw new AccessDeniedException("Аккаунт заблокирован.");
        if (authentication.getAuthorities().stream()
                .noneMatch(authority -> authority.getAuthority().equals("ROLE_" + user.getRole().name()))) {
            throw new AccessDeniedException("Роль аккаунта изменена. Войдите в систему заново.");
        }
        return user;
    }

    public School school(User user, Long requestedSchoolId) {
        if (user.getRole() == Role.OWNER) {
            if (requestedSchoolId == null) return null;
            return schools.findById(requestedSchoolId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Школа не найдена."));
        }
        School school = user.getSchool();
        if (school == null || (requestedSchoolId != null && !school.getId().equals(requestedSchoolId))) {
            throw new AccessDeniedException("Нет доступа к расписанию этой школы.");
        }
        return school;
    }

    public boolean canEdit(User user) {
        return user.getRole() == Role.DIRECTOR;
    }

    public Long visibleClass(User user, Long requestedClassId) {
        if (user.getRole() != Role.STUDENT) return requestedClassId;
        if (user.getStudentClassId() == null) {
            throw new AccessDeniedException("Класс ученика не назначен. Обратитесь к директору.");
        }
        if (requestedClassId != null && !requestedClassId.equals(user.getStudentClassId())) {
            throw new AccessDeniedException("Ученику доступно только расписание своего класса.");
        }
        return user.getStudentClassId();
    }

    public Long visibleTeacher(User user, boolean onlyMine) {
        if (!onlyMine) return null;
        if (user.getRole() != Role.TEACHER) throw new AccessDeniedException("Этот фильтр доступен преподавателю.");
        if (user.getTeacherId() == null) {
            throw new AccessDeniedException("Аккаунт не связан с преподавателем. Обратитесь к директору.");
        }
        return user.getTeacherId();
    }

    public List<School> availableSchools(User user) {
        if (user.getRole() != Role.OWNER) throw new AccessDeniedException("Нет доступа к списку школ.");
        return schools.findAllByOrderByNameAsc();
    }
}
