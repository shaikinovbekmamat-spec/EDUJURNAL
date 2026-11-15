package com.example.edujurnal.service;
import com.example.edujurnal.entity.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class JournalBrowserService {
 private final JdbcTemplate jdbc; private final ScheduleAccessService access; private final JournalService journal;
 public JournalBrowserService(JdbcTemplate jdbc,ScheduleAccessService access,JournalService journal){this.jdbc=jdbc;this.access=access;this.journal=journal;}
 public record Choice(long id,String name) {}
 public record Selection(boolean staff,Long classId,Long studentId,List<Choice> classes,List<Choice> students,List<String> subjects,int page,boolean more,String search) {}
 public Selection select(Authentication auth,boolean family,Long classId,Long studentId,String search,int page){
  var u=access.currentUser(auth);
  boolean staff=u.getRole()==Role.DIRECTOR || (u.getRole()==Role.TEACHER&&!family);
  if(!staff) return new Selection(false,null,null,List.of(),List.of(),List.of(),0,false,"");
  page=Math.max(0,Math.min(page,100000)); search=search==null?"":search.strip();
  if(search.length()>100) search=search.substring(0,100);
  String scope=" c.school_id=?";var args=new ArrayList<Object>();args.add(u.getSchool().getId());
  if(u.getRole()==Role.TEACHER){scope+=" and exists(select 1 from teaching_assignment a where a.class_id=c.id and a.teacher_id=?)";args.add(u.getTeacherId());}
  if(classId!=null){var check=new ArrayList<>(args);check.add(classId);if(jdbc.queryForObject("select count(*) from schedule_class c where "+scope+" and c.id=?",Long.class,check.toArray())==0)throw new AccessDeniedException("Класс недоступен.");}
  List<Choice> classes=List.of(),students=List.of();boolean more=false;
  if(classId==null){var a=new ArrayList<>(args);a.add("%"+search.toLowerCase(Locale.ROOT)+"%");a.add(page*30);classes=jdbc.query("select c.id,c.name from schedule_class c where "+scope+" and lower(c.name) like ? order by c.name,c.id limit 31 offset ?",(r,n)->new Choice(r.getLong(1),r.getString(2)),a.toArray());more=classes.size()>30;classes=classes.stream().limit(30).toList();}
  else if(studentId==null){students=jdbc.query("select id,full_name from users where student_class_id=? and role='STUDENT' and lower(full_name) like ? order by full_name,id limit 31 offset ?",(r,n)->new Choice(r.getLong(1),r.getString(2)),classId,"%"+search.toLowerCase(Locale.ROOT)+"%",page*30);more=students.size()>30;students=students.stream().limit(30).toList();}
  if(studentId!=null && (classId==null || jdbc.queryForObject("select count(*) from users where id=? and student_class_id=? and role='STUDENT'",Long.class,studentId,classId)==0))throw new AccessDeniedException("Ученик не относится к выбранному классу.");
  return new Selection(true,classId,studentId,classes,students,List.of(),page,more,search);
 }
 public JournalService.View prepare(Authentication auth,boolean family,Long childId,Long yearId,Long quarterId,Long classId,Long studentId,String subject,String search,int page,org.springframework.ui.Model model,String path){
  var selection=select(auth,family,classId,studentId,search,page);
  page=Math.max(0,Math.min(page,100000));
  Long filter=selection.staff()?(studentId==null?-1L:studentId):null;
  var view=journal.view(auth,family,childId,yearId,quarterId,filter,subject,31,page*30);
  model.addAttribute("browser",selection);model.addAttribute("path",path);model.addAttribute("subjectFilter",subject==null?"":subject);model.addAttribute("pageNumber",page);
  model.addAttribute("more",view.entries().size()>30);model.addAttribute("rows",view.entries().stream().limit(30).toList());
  model.addAttribute("archivedYear",view.yearId()!=null && jdbc.queryForObject("select archived from academic_year where id=?",Boolean.class,view.yearId()));
  model.addAttribute("user",access.currentUser(auth));model.addAttribute("view",view);
  model.addAttribute("subjectChoices",view.yearId()==null?List.of():jdbc.queryForList("select s.name from schedule_subject s join academic_year y on y.school_id=s.school_id where y.id=? order by s.name",String.class,view.yearId()));
  model.addAttribute("selectedName",selection.studentId()==null?"":jdbc.queryForObject("select full_name from users where id=?",String.class,selection.studentId()));
  return view;
 }}
