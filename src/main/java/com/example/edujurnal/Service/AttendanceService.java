package com.example.edujurnal.service;

import com.example.edujurnal.entity.Role;
import com.example.edujurnal.exception.ScheduleException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class AttendanceService {
 private final JdbcTemplate jdbc;
 private final ScheduleAccessService access;
 private final LessonJournalService lessons; private final JournalService journal;
 public AttendanceService(JdbcTemplate jdbc, ScheduleAccessService access, LessonJournalService lessons, JournalService journal) {
  this.jdbc=jdbc; this.access=access; this.lessons=lessons; this.journal=journal;
 }
 public static String label(String status) {
  return switch(status) {case "PRESENT" -> "Присутствовал"; case "ABSENT" -> "Отсутствовал"; case "LATE" -> "Опоздал"; default -> "Не отмечено";};
 }
 public record Mark(String status,String reason,long version) { public String label(){return AttendanceService.label(status);} }
 public Mark mark(long lesson,long student) {
  var rows=jdbc.query("select * from attendance where lesson_id=? and student_id=?",(r,n)->new Mark(r.getString("status"),r.getString("reason"),r.getLong("version")),lesson,student);
  return rows.isEmpty()?new Mark("", "",0):rows.getFirst();
 }
 @Transactional
 public void save(Authentication auth,long lesson,long student,String status,String reason,String correction,long version) {
  var user=access.currentUser(auth);
  if(user.getRole()!=Role.TEACHER) throw new AccessDeniedException("Посещаемость отмечает учитель урока.");
  if(!Set.of("PRESENT","ABSENT","LATE").contains(status)) throw new ScheduleException("Выберите статус посещаемости.");
  reason=reason.strip(); correction=correction.strip();
  if(reason.length()>1000 || correction.length()>1000) throw new ScheduleException("Максимальная длина причины — 1000 символов.");
  jdbc.queryForList("select id from schedule_school where id=? for update",user.getSchool().getId());
  var page=lessons.page(auth,lesson);
  if(page.students().stream().noneMatch(s->s.id()==student)) throw new AccessDeniedException("Ученик не входит в состав урока.");
  if(page.lesson().date().isAfter(journal.today() )) throw new ScheduleException("Нельзя отмечать будущий урок.");
  var old=mark(lesson,student);
  if(old.status().equals(status) && old.reason().equals(reason)) return;
  if(old.version()!=version) throw new ScheduleException("Отметка уже изменена. Обновите страницу и повторите действие.");
  if(version>0 && correction.isBlank()) throw new ScheduleException("Укажите причину исправления.");
  if(version==0) jdbc.update("insert into attendance values(?,?,?,?,1)",lesson,student,status,reason);
  else jdbc.update("update attendance set status=?,reason=?,version=version+1 where lesson_id=? and student_id=?",status,reason,lesson,student);
  jdbc.update("insert into attendance_history(lesson_id,student_id,actor_id,old_status,new_status,reason,correction) values(?,?,?,?,?,?,?)",lesson,student,user.getId(),old.status().isEmpty()?null:old.status(),status,reason,correction);
  if(status.equals("ABSENT")) jdbc.update("""
   insert into attendance_notification(recipient_id,lesson_id,student_id)
   select p.parent_id,?,p.student_id from parent_student p where p.student_id=?
   and not exists(select 1 from attendance_notification n where n.recipient_id=p.parent_id and n.lesson_id=? and n.student_id=p.student_id)
   """,lesson,student,lesson);
 }
 public List<Map<String,Object>> notifications(Authentication auth) {return notifications(auth,null,false,0,100);}
 public List<Map<String,Object>> notifications(Authentication auth,Long childId,boolean unread,int page,int limit) {
  var user=access.currentUser(auth);
  if(childId!=null && journal.children(user).stream().noneMatch(c->c.id()==childId)) throw new AccessDeniedException("Нет подтверждённой связи с ребёнком.");
  String sql="""
   select n.id,n.read_at,u.full_name,s.name as subject,l.lesson_date,l.start_time,a.status,a.reason,a.version
   from attendance_notification n join parent_student p on p.parent_id=n.recipient_id and p.student_id=n.student_id
   join attendance a on a.lesson_id=n.lesson_id and a.student_id=n.student_id
   join users u on u.id=n.student_id join schedule_lesson l on l.id=n.lesson_id join schedule_subject s on s.id=l.subject_id
   where n.recipient_id=?
   """;
  var args=new ArrayList<Object>();args.add(user.getId());
  if(childId!=null){sql+=" and n.student_id=?";args.add(childId);}
  if(unread) sql+=" and n.read_at is null";
  sql+=" order by l.lesson_date desc,l.start_time desc,n.id desc limit ? offset ?";args.add(limit);args.add(Math.max(0,Math.min(page,100000))*30);
  return jdbc.queryForList(sql,args.toArray());
 }
 public List<JournalService.Child> children(Authentication auth){return journal.children(access.currentUser(auth));} @Transactional public void read(Authentication auth,long id) {
  if(jdbc.update("update attendance_notification set read_at=current_timestamp where id=? and recipient_id=? and exists(select 1 from parent_student p where p.parent_id=recipient_id and p.student_id=attendance_notification.student_id)",id,access.currentUser(auth).getId())==0)
   throw new AccessDeniedException("Уведомление недоступно.");
 }
 public List<Map<String,Object>> history(Authentication auth,long lesson,long student) {
  lessons.page(auth,lesson);
  return jdbc.queryForList("select h.*,u.full_name from attendance_history h join users u on u.id=h.actor_id where h.lesson_id=? and h.student_id=? order by h.id desc",lesson,student);
 }
}
