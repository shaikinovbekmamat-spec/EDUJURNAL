package com.example.edujurnal.controller;

import com.example.edujurnal.exception.ScheduleException;
import com.example.edujurnal.service.HomeworkService;
import com.example.edujurnal.service.ScheduleAccessService;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/homework")
public class HomeworkController {
    private final HomeworkService service;
    private final ScheduleAccessService access;

    public HomeworkController(HomeworkService service, ScheduleAccessService access) {
        this.service = service;
        this.access = access;
    }

    public record Form(Long classId, Long subjectId, String title, String body,
                       @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dueDate, String mode, String token) {}

    @GetMapping
    public String list(Authentication auth, @RequestParam(defaultValue="0") int page, Model model) {
        page = Math.max(0, Math.min(page, 100000));
        var rows = service.list(auth, page);
        model.addAttribute("user", access.currentUser(auth));
        model.addAttribute("rows", rows.stream().limit(20).toList());
        model.addAttribute("pageNumber", page);
        model.addAttribute("more", rows.size() > 20);
        return "homework/list";
    }

    @GetMapping("/new")
    public String form(Authentication auth, Model model) {
        model.addAttribute("form", new Form(null, null, "", "", service.today(), "ONLINE", UUID.randomUUID().toString()));
        return prepareForm(auth, model);
    }

    private String prepareForm(Authentication auth, Model model) {
        var choices = service.choices(auth);
        var classes = new LinkedHashMap<Long,String>();
        choices.forEach(c -> classes.put(c.classId(), c.className()));
        model.addAttribute("user", access.currentUser(auth));
        model.addAttribute("choices", choices);
        model.addAttribute("classes", classes);
        return "homework/form";
    }

    @PostMapping
    public String create(Authentication auth, @ModelAttribute("form") Form form, BindingResult binding,
                         Model model, HttpServletResponse response, RedirectAttributes flash) {
        try {
            if (binding.hasErrors()) throw new ScheduleException("Проверьте формат даты и выбранные класс и предмет.");
            if (form.classId() == null || form.subjectId() == null)
                throw new ScheduleException("Выберите класс и предмет.");
            long id = service.create(auth, form.classId(), form.subjectId(), form.title(), form.body(),
                    form.dueDate(), form.mode(), form.token());
            flash.addFlashAttribute("success", "Домашнее задание опубликовано для всего класса.");
            return "redirect:/homework/" + id;
        } catch (ScheduleException ex) {
            response.setStatus(400);
            model.addAttribute("error", ex.getMessage());
            return prepareForm(auth, model);
        }
    }

    @GetMapping("/{id}")
    public String detail(Authentication auth, @PathVariable long id, Model model) {
        model.addAttribute("detail", service.detail(auth, id));
        model.addAttribute("user", access.currentUser(auth));
        if (!model.containsAttribute("answerText")) model.addAttribute("answerText", "");
        return "homework/detail";
    }

    @PostMapping("/{id}/submit")
    public String submit(Authentication auth, @PathVariable long id,
                         @RequestParam(defaultValue="") String text,
                         @RequestParam(required=false) MultipartFile[] files,
                         Model model, HttpServletResponse response, RedirectAttributes flash) {
        try {
            service.submit(auth, id, text, files);
            flash.addFlashAttribute("success", "Ответ отправлен. Предыдущие версии сохранены.");
            return "redirect:/homework/" + id;
        } catch (ScheduleException ex) {
            model.addAttribute("answerText", text);
            model.addAttribute("error", ex.getMessage() + " Если вы прикладывали файлы, выберите их заново.");
            response.setStatus(400);
            return detail(auth, id, model);
        }
    }

    @PostMapping("/{id}/check")
    public String check(Authentication auth, @PathVariable long id, @RequestParam long studentId,
                        @RequestParam int version, @RequestParam String status,
                        @RequestParam(defaultValue="") String comment, Model model,
                        HttpServletResponse response, RedirectAttributes flash) {
        try {
            service.check(auth, id, studentId, version, status, comment);
            flash.addFlashAttribute("success", "Результат проверки сохранён.");
            return "redirect:/homework/" + id + "#student-" + studentId;
        } catch (ScheduleException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("rejectedStudent", studentId);
            model.addAttribute("rejectedComment", comment);
            response.setStatus(400);
            return detail(auth, id, model);
        }
    }

    @PostMapping("/{id}/paper")
    public String paper(Authentication auth, @PathVariable long id, @RequestParam long studentId, RedirectAttributes flash) {
        service.markPaper(auth, id, studentId);
        flash.addFlashAttribute("success", "Бумажная работа отмечена как сданная.");
        return "redirect:/homework/" + id + "#student-" + studentId;
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<byte[]> download(Authentication auth, @PathVariable long fileId) {
        var file = service.download(auth, fileId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").header("Cache-Control", "private, no-store")
                .contentLength(file.data().length).body(file.data());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String uploadTooLarge(RedirectAttributes flash) {
        flash.addFlashAttribute("error", "Файл превышает 10 МБ или общий размер отправки превышает 51 МБ. Ответ не сохранён.");
        return "redirect:/homework";
    }
}
