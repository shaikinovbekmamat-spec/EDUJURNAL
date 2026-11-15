package com.example.edujurnal.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.example.edujurnal.service.BackupService;

@Controller
@PreAuthorize("hasRole('OWNER')")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping("/backup")
    public String backupPage(Model model) {
        model.addAttribute("backups", backupService.listBackups());
        model.addAttribute("lastBackup", backupService.getLastBackupInfo());
        return "backup/index";
    }

    @PostMapping("/backup/create")
    public String createBackup(RedirectAttributes flash) {
        try {
            String backupFile = backupService.createBackup();
            flash.addFlashAttribute("success", "Резервная копия создана успешно: " + backupFile);
        } catch (Exception e) {
            flash.addFlashAttribute("error", "Ошибка создания резервной копии: " + e.getMessage());
        }
        return "redirect:/backup";
    }

    @PostMapping("/backup/restore")
    public String restoreBackup(@RequestParam String backupFile, RedirectAttributes flash) {
        try {
            backupService.restoreBackup(backupFile);
            flash.addFlashAttribute("success", "База данных восстановлена из резервной копии");
        } catch (Exception e) {
            flash.addFlashAttribute("error", "Ошибка восстановления: " + e.getMessage());
        }
        return "redirect:/backup";
    }

    @PostMapping("/backup/delete")
    public String deleteBackup(@RequestParam String backupFile, RedirectAttributes flash) {
        try {
            backupService.deleteBackup(backupFile);
            flash.addFlashAttribute("success", "Резервная копия удалена");
        } catch (Exception e) {
            flash.addFlashAttribute("error", "Ошибка удаления: " + e.getMessage());
        }
        return "redirect:/backup";
    }
}
