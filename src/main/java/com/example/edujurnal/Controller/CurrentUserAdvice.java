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
import org.springframework.context.i18n.LocaleContextHolder;

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
        boolean kyrgyz="ky".equals(LocaleContextHolder.getLocale().getLanguage());
        boolean classTeacher=role==Role.TEACHER && academic.getManagedClass(auth)!=null;
        String home=classTeacher ? "/class-teacher/dashboard" : "/"+role.name().toLowerCase(java.util.Locale.ROOT)+"/dashboard";
        String label=classTeacher ? (kyrgyz ? "Класс жетекчи" : "Классный руководитель") : switch(role) {
            case OWNER -> kyrgyz ? "Ээси" : "Владелец";
            case DIRECTOR -> kyrgyz ? "Директор" : "Директор";
            case TEACHER -> kyrgyz ? "Мугалим" : "Учитель";
            case STUDENT -> kyrgyz ? "Окуучу" : "Ученик";
            case PARENT -> kyrgyz ? "Ата-эне" : "Родитель";
        };
        var items=new ArrayList<NavigationItem>();
        if(role!=Role.OWNER) add(items,request,kyrgyz ? "Башкы бет" : "Главная",home,"⌂");
        if(classTeacher) {
            add(items,request,kyrgyz ? "Класстын журналы" : "Журнал класса","/class-teacher/journal","☆");
            add(items,request,kyrgyz ? "Ата-энелер" : "Родители","/class-teacher/parents","○");
            add(items,request,kyrgyz ? "Каттоо жазуулары" : "Учётные записи","/class-teacher/accounts","⚙");
        }
        if(role==Role.OWNER || role==Role.DIRECTOR) add(items,request,kyrgyz ? "Мектепти башкаруу" : "Управление школой","/management","⚙");
        if(role==Role.TEACHER) add(items,request,kyrgyz ? "Бааларды коюу" : "Выставить оценки","/journal/today","✎");
        if(role!=Role.OWNER) add(items,request,role==Role.TEACHER ? (kyrgyz ? "Менин журналым" : "Мой журнал") : role==Role.DIRECTOR ? (kyrgyz ? "Журнал жана баалар" : "Журнал и оценки") : (kyrgyz ? "Баалар" : "Оценки"),"/journal","☆");
        if(role==Role.TEACHER) add(items,request,kyrgyz ? "Балдарым" : "Мои дети","/journal?family=true","○");
        if(role!=Role.OWNER) add(items,request,kyrgyz ? "Сабак жадыбалы" : "Расписание","/schedule","▦");
        if(role!=Role.OWNER) add(items,request,kyrgyz ? "Аналитика" : "Аналитика","/analytics","↗");
        if(role==Role.STUDENT || role==Role.TEACHER) add(items,request,kyrgyz ? "Үй тапшырмалары" : "Домашние задания","/homework","▤");
        if(role==Role.STUDENT) {
            
            
            add(items,request,kyrgyz ? "Графиктер" : "Графики",home+"#progress","↗");
        }
        if(role!=Role.OWNER) add(items,request,kyrgyz ? "Катышуу" : "Посещаемость","/attendance","✓"); if(role==Role.PARENT || role==Role.TEACHER || role==Role.STUDENT) add(items,request,kyrgyz ? "Билдирмелер" : "Уведомления","/notifications","◉"); if(role!=Role.OWNER){add(items,request,kyrgyz ? "Жарыялар" : "Объявления","/messages","◉");add(items,request,kyrgyz ? "Эскертүүлөр" : "Замечания","/messages?kind=REMARK","◉");} add(items,request,kyrgyz ? "Профиль" : "Профиль","/profile","○");
        return new Navigation(user.getFullName()==null || user.getFullName().isBlank() ? user.getLogin() : user.getFullName(),user.getLogin(),label,home,List.copyOf(items));
    }

    private void add(List<NavigationItem> items,HttpServletRequest request,String label,String href,String icon) {
        String path=request.getRequestURI();
        boolean family="true".equals(request.getParameter("family"));
        boolean active=(href.equals("/notifications") && path.startsWith("/notifications")) || href.equals(path) || (href.equals("/schedule") && path.startsWith("/schedule/"));
        if(href.equals("/journal")) active=path.equals("/journal") && !family;
        if(href.equals("/journal?family=true")) active=path.equals("/journal") && family;
        if(href.equals("/journal/today")) active=path.startsWith("/journal/");
        if(href.equals("/homework")) active=path.equals("/homework") || path.startsWith("/homework/");
        items.add(new NavigationItem(label,href,icon,active));
    }
    @ModelAttribute("currentLogin")
    public String currentLogin(Authentication authentication) {
        return authentication == null ? "" : authentication.getName();
    }
}
