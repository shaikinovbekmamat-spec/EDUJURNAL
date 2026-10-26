package com.example.edujurnal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String conflictingRecord(Model model) {
        model.addAttribute("message", "Запись уже существует или связана с сохранёнными данными. Обновите страницу и проверьте введённые значения.");
        return "schedule/error";
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalidInput(Model model) {
        model.addAttribute("message", "Проверьте формат полей: даты, выбранные записи и целые числовые значения.");
        return "schedule/error";
    }
    @ExceptionHandler(ScheduleException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String scheduleError(ScheduleException ex, Model model) {
        model.addAttribute("message", ex.getMessage());
        return "schedule/error";
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String accessDenied(AccessDeniedException ex, Model model) {
        model.addAttribute("message", ex.getMessage());
        return "schedule/error";
    }
}
