package com.example.edujurnal.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import com.example.edujurnal.entity.Role;
import com.example.edujurnal.service.AcademicService;
import com.example.edujurnal.service.ScheduleAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;

@ControllerAdvice
public class CurrentUserAdvice {
    private final ScheduleAccessService access;
    private final AcademicService academic;

    public CurrentUserAdvice(ScheduleAccessService access, AcademicService academic) {
        this.access=access;
        this.academic=academic;
    }

    public record NavigationItem(String label,String href,String icon,boolean active) {}
    public record Navigation(String name,String login,String roleLabel,String home,List<NavigationItem> items) {}

    @ModelAttribute("navigation")
    public Navigation navigation(Authentication auth,HttpServletRequest request) {
        if(auth==null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return null;
        com.example.edujurnal.entity.User user;
        try { user=access.currentUser(auth); }
        catch(AccessDeniedException ex) { return null; }
        Role role=user.getRole();
        boolean classTeacher=role==Role.TEACHER && academic.getManagedClass(auth)!=null;
        String home=classTeacher ? "/class-teacher/dashboard" : "/"+role.name().toLowerCase(java.util.Locale.ROOT)+"/dashboard";
        String label=classTeacher ? "Классный руководитель" : switch(role) {
            case OWNER -> "Владелец";
            case DIRECTOR -> "Директор";
            case TEACHER -> "Учитель";
            case STUDENT -> "Ученик";
            case PARENT -> "Родитель";
        };
        var items=new ArrayList<NavigationItem>();
        add(items,request,"Главная",home,"⌂");
        if(classTeacher) {
            add(items,request,"Журнал класса","/class-teacher/journal","☆");
            add(items,request,"Родители","/class-teacher/parents","○");
            add(items,request,"Учётные записи","/class-teacher/accounts","⚙");
        }
        if(role==Role.OWNER || role==Role.DIRECTOR) add(items,request,"Управление школой","/management","⚙");
        if(role==Role.TEACHER) add(items,request,"Выставить оценки","/journal/today","✎");
        if(role!=Role.OWNER) add(items,request,role==Role.TEACHER ? "Мой журнал" : role==Role.DIRECTOR ? "Журнал и оценки" : "Оценки","/journal","☆");
        if(role==Role.TEACHER) add(items,request,"Мои дети","/journal?family=true","○");
        add(items,request,"Расписание","/schedule","▦");
        if(role==Role.STUDENT) {
            add(items,request,"Посещаемость",home+"#attendance","✓");
            add(items,request,"Домашние задания",home+"#homework","▤");
            add(items,request,"Объявления",home+"#announcements","◉");
            add(items,request,"Графики",home+"#progress","↗");
        }
        add(items,request,"Профиль","/profile","○");
        return new Navigation(user.getFullName()==null || user.getFullName().isBlank() ? user.getLogin() : user.getFullName(),user.getLogin(),label,home,List.copyOf(items));
    }

    private void add(List<NavigationItem> items,HttpServletRequest request,String label,String href,String icon) {
        String path=request.getRequestURI();
        boolean family="true".equals(request.getParameter("family"));
        boolean active=href.equals(path) || (href.equals("/schedule") && path.startsWith("/schedule/"));
        if(href.equals("/journal")) active=path.equals("/journal") && !family;
        if(href.equals("/journal?family=true")) active=path.equals("/journal") && family;
        if(href.equals("/journal/today")) active=path.startsWith("/journal/");
        items.add(new NavigationItem(label,href,icon,active));
    }
    @ModelAttribute("currentLogin")
    public String currentLogin(Authentication authentication) {
        return authentication == null ? "" : authentication.getName();
    }
}
