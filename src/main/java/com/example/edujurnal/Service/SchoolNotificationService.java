package com.example.edujurnal.service;
import com.example.edujurnal.entity.Lesson;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@Transactional
public class SchoolNotificationService {
 private final JdbcTemplate jdbc;
 public SchoolNotificationService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Set<Long> students(long school,long classId,Long subgroup,java.time.LocalDate date){
  return new HashSet<>(jdbc.queryForList("select u.id from users u where u.school_id=? and u.student_class_id=? and u.role='STUDENT' and u.enabled=true and (?=true or exists(select 1 from schedule_membership m where m.student_id=u.id and m.subgroup_id=? and m.active_from<=? and (m.active_to is null or m.active_to>=?)))",Long.class,school,classId,subgroup==null,subgroup,date,date));
 }
 public void send(long recipient,String key,String body,String link,Long message,Long student){
  jdbc.update("insert into school_notification(recipient_id,event_key,body,link,message_id,student_id) select ?,?,?,?,?,? where not exists(select 1 from school_notification where recipient_id=? and event_key=?)",recipient,key,body,link,message,student,recipient,key);
 }
 public record Snapshot(long school,long id,String description,Set<Long> recipients,String link){}
 public Snapshot snapshot(Lesson l){
  var recipients=students(l.getSchoolId(),l.getClassId(),l.getSubgroupId(),l.getDate());
  recipients.addAll(jdbc.queryForList("select id from users where school_id=? and teacher_id=? and enabled=true",Long.class,l.getSchoolId(),l.getTeacherId()));
  String subject=jdbc.queryForObject("select name from schedule_subject where id=?",String.class,l.getSubjectId());
  String room=jdbc.queryForObject("select name from schedule_room where id=?",String.class,l.getRoomId());
  String teacher=jdbc.queryForObject("select name from schedule_teacher where id=?",String.class,l.getTeacherId());
  return new Snapshot(l.getSchoolId(),l.getId(),subject+" · "+l.getDate()+" "+l.getStartTime()+" · кабинет "+room+" · "+teacher,recipients,"/schedule?date="+l.getDate());
 }
 public void changed(Snapshot before,Lesson after){
  Snapshot next=snapshot(after); if(before.description().equals(next.description()) && before.recipients().equals(next.recipients()))return;
  var recipients=new HashSet<>(before.recipients());recipients.addAll(next.recipients());
  for(long id:recipients)send(id,"lesson:"+after.getId()+":"+after.getVersion(),"Урок изменён. Было: "+before.description()+". Стало: "+next.description(),next.link(),null,null);
 }
 public void cancelled(Snapshot before,String key){for(long id:before.recipients())send(id,key,"Урок отменён: "+before.description(),before.link(),null,null);}
 public void batchChanged(List<Snapshot> before,List<Snapshot> after,String key,java.time.LocalDate start,java.time.LocalDate end) {
  var previous=new HashMap<Long,Snapshot>();
  for(var item:before)previous.put(item.id(),item);
  var recipients=new HashSet<Long>();
  for(var item:after){
   var old=previous.remove(item.id());
   if(old!=null && old.description().equals(item.description()) && old.recipients().equals(item.recipients()))continue;
   recipients.addAll(item.recipients());
   if(old!=null)recipients.addAll(old.recipients());
  }
  for(var removed:previous.values())recipients.addAll(removed.recipients());
  var format=java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
  for(long recipient:recipients)send(recipient,key,"Расписание изменено на период "+start.format(format)+" — "+end.format(format)+". Откройте расписание, чтобы посмотреть актуальные уроки.","/schedule?date="+start,null,null);
 }
}
