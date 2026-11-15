package com.example.edujurnal.service;
import com.example.edujurnal.entity.*;
import com.example.edujurnal.exception.ScheduleException;
import java.time.*;import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@Transactional(readOnly=true)
public class SchoolMessageService {
 private final JdbcTemplate jdbc;private final ScheduleAccessService access;private final SchoolNotificationService notifications;private final Clock clock;
 public SchoolMessageService(JdbcTemplate jdbc,ScheduleAccessService access,SchoolNotificationService notifications,Clock clock){this.jdbc=jdbc;this.access=access;this.notifications=notifications;this.clock=clock;}
 private User author(Authentication auth){var u=access.currentUser(auth);if(u.getRole()!=Role.TEACHER && u.getRole()!=Role.DIRECTOR)throw new AccessDeniedException("Публикация доступна учителю или директору.");return u;}
 private void assigned(User u,long classId){
  if(jdbc.queryForObject("select count(*) from schedule_class where id=? and school_id=?",Long.class,classId,u.getSchool().getId())==0)throw new AccessDeniedException("Класс недоступен.");
  if(u.getRole()==Role.TEACHER && jdbc.queryForObject("select count(*) from teaching_assignment where class_id=? and teacher_id=?",Long.class,classId,u.getTeacherId())==0)throw new AccessDeniedException("Класс вам не назначен.");
 }
 public List<Map<String,Object>> classes(Authentication auth){var u=author(auth);return jdbc.queryForList("select c.id,c.name from schedule_class c where c.school_id=? and (?='DIRECTOR' or exists(select 1 from teaching_assignment a where a.class_id=c.id and a.teacher_id=?)) order by c.name",u.getSchool().getId(),u.getRole().name(),u.getTeacherId());}
 public List<Map<String,Object>> students(Authentication auth,Long classId){if(classId==null)return List.of();var u=author(auth);assigned(u,classId);return jdbc.queryForList("select id,full_name from users where student_class_id=? and role='STUDENT' order by full_name",classId);}
 private String text(String s,int max){if(s==null||s.isBlank()||s.length()>max)throw new ScheduleException("Заполните текст; максимальная длина — "+max+" символов.");return s.strip();}
 @Transactional public long create(Authentication auth,String kind,String title,String body,List<Long> classes,Long subgroup,Long student,Long lesson,LocalDate date,String token){
  var u=author(auth);long school=u.getSchool().getId();jdbc.queryForList("select id from schedule_school where id=? for update",school);
  token=text(token,100);title=text(title,200);body=text(body,10000);
  var existing=jdbc.queryForList("select id from school_message where request_token=? and author_id=?",Long.class,token,u.getId());if(!existing.isEmpty())return existing.getFirst();
  if(date==null)date=LocalDate.now(clock);if(date.isAfter(LocalDate.now(clock)))throw new ScheduleException("Дата не может быть в будущем.");
  AcademicYearArchive.requireOpenDate(jdbc,school,date);
  Set<Long> recipients=new HashSet<>();
  if("REMARK".equals(kind)){
   if(u.getRole()!=Role.TEACHER || student==null)throw new AccessDeniedException("Замечание создаёт учитель ученику своего класса.");
   var cls=jdbc.queryForList("select student_class_id from users where id=? and school_id=? and role='STUDENT' and enabled=true",Long.class,student,school);
   if(cls.isEmpty()||cls.getFirst()==null)throw new AccessDeniedException("Ученик недоступен.");assigned(u,cls.getFirst());
   if(lesson!=null && jdbc.queryForObject("select count(*) from schedule_lesson where id=? and school_id=? and teacher_id=? and class_id=?",Long.class,lesson,school,u.getTeacherId(),cls.getFirst())==0)throw new AccessDeniedException("Урок недоступен.");
   if(lesson!=null){var l=jdbc.queryForMap("select subgroup_id,lesson_date from schedule_lesson where id=?",lesson);Long group=l.get("subgroup_id")==null?null:((Number)l.get("subgroup_id")).longValue();if(!notifications.students(school,cls.getFirst(),group,((java.sql.Date)l.get("lesson_date")).toLocalDate()).contains(student))throw new AccessDeniedException("Ученик не входит в подгруппу урока.");}
   recipients.add(student);
  }else if("ANNOUNCEMENT".equals(kind)){
   student=null;lesson=null;
   if(classes==null||classes.isEmpty()){
    if(u.getRole()!=Role.DIRECTOR)throw new AccessDeniedException("Объявление всей школе публикует директор.");
    if(subgroup!=null)throw new ScheduleException("Выберите класс подгруппы.");
    recipients.addAll(jdbc.queryForList("select id from users where school_id=? and role='STUDENT' and enabled=true and student_class_id is not null",Long.class,school));
   }else{
    if(subgroup!=null && classes.size()!=1)throw new ScheduleException("Для подгруппы выберите один класс.");
    for(long cls:new HashSet<>(classes)){assigned(u,cls);
     if(subgroup!=null && jdbc.queryForObject("select count(*) from schedule_subgroup where id=? and class_id=? and school_id=?",Long.class,subgroup,cls,school)==0)throw new AccessDeniedException("Подгруппа недоступна.");
     recipients.addAll(notifications.students(school,cls,subgroup,date));
    }
   }
  }else throw new ScheduleException("Неизвестный тип записи.");
  jdbc.update("insert into school_message(school_id,author_id,kind,title,body,student_id,lesson_id,event_date,request_token) values(?,?,?,?,?,?,?,?,?)",school,u.getId(),kind,title,body,student,lesson,date,token);
  long id=jdbc.queryForObject("select id from school_message where request_token=?",Long.class,token);
  for(long child:recipients){jdbc.update("insert into message_audience values(?,?)",id,child);
   if(kind.equals("ANNOUNCEMENT")){
    notifications.send(child,"message:"+id,title,"/messages/"+id,id,child);
    for(long parent:jdbc.queryForList("select parent_id from parent_student where student_id=?",Long.class,child))notifications.send(parent,"message:"+id+":"+child,title,"/messages/"+id,id,child);
   }
  }
  return id;
 }
 private String allowed(){return """
 (m.author_id=? or (?='DIRECTOR' and m.school_id=?) or exists(select 1 from message_audience a where a.message_id=m.id and (a.student_id=? or exists(select 1 from parent_student p where p.parent_id=? and p.student_id=a.student_id))) or (m.kind='REMARK' and exists(select 1 from users s join class_teacher c on c.class_id=s.student_class_id where s.id=m.student_id and c.teacher_user_id=?)))
 """;}
 private Object[] args(User u){return new Object[]{u.getId(),u.getRole().name(),u.getSchool()==null?-1:u.getSchool().getId(),u.getId(),u.getId(),u.getId()};}
 public List<Map<String,Object>> list(Authentication auth,String kind,int page){var u=access.currentUser(auth);var a=new ArrayList<>(Arrays.asList(args(u)));a.add(kind);a.add(Math.max(0,Math.min(page,100000))*20);return jdbc.queryForList("select m.*,u.full_name as author from school_message m join users u on u.id=m.author_id where "+allowed()+" and m.kind=? order by m.created_at desc,m.id desc limit 21 offset ?",a.toArray());}
 public Map<String,Object> get(Authentication auth,long id){var u=access.currentUser(auth);var a=new ArrayList<>(Arrays.asList(args(u)));a.add(id);var rows=jdbc.queryForList("select m.*,u.full_name as author from school_message m join users u on u.id=m.author_id where "+allowed()+" and m.id=?",a.toArray());if(rows.isEmpty())throw new AccessDeniedException("Запись недоступна.");return rows.getFirst();}
 @Transactional public void correct(Authentication auth,long id,long version,String body,String reason){var u=author(auth);body=text(body,10000);reason=text(reason,1000);var row=get(auth,id);if(!row.get("kind").equals("REMARK")||((Number)row.get("author_id")).longValue()!=u.getId())throw new AccessDeniedException("Исправление доступно автору замечания.");
  jdbc.queryForList("select id from schedule_school where id=? for update",u.getSchool().getId());
  AcademicYearArchive.requireOpenDate(jdbc,u.getSchool().getId(),((java.sql.Date)row.get("event_date")).toLocalDate());
  if(jdbc.update("update school_message set body=?,version=version+1 where id=? and version=?",body,id,version)==0)throw new ScheduleException("Замечание уже изменено. Обновите страницу.");
  jdbc.update("insert into message_history(message_id,actor_id,old_body,new_body,reason) values(?,?,?,?,?)",id,u.getId(),row.get("body"),body,reason);
 }
 public List<Map<String,Object>> history(Authentication auth,long id){get(auth,id);return jdbc.queryForList("select h.*,u.full_name from message_history h join users u on u.id=h.actor_id where message_id=? order by h.id desc",id);}
 public List<Map<String,Object>> notifications(Authentication auth,boolean unread,int page) {
  var u=access.currentUser(auth);
  return jdbc.queryForList("select n.* from school_notification n where n.recipient_id=? and (n.message_id is null or n.student_id=? or exists(select 1 from parent_student p where p.parent_id=? and p.student_id=n.student_id))"+(unread ? " and n.read_at is null" : "")+" order by n.created_at desc,n.id desc limit 21 offset ?",u.getId(),u.getId(),u.getId(),Math.max(0,Math.min(page,100000))*20);
 }
 public List<Map<String,Object>> inbox(Authentication auth,int page){var u=access.currentUser(auth);return jdbc.queryForList("select n.* from school_notification n where n.recipient_id=? and (n.message_id is null or n.student_id=? or exists(select 1 from parent_student p where p.parent_id=? and p.student_id=n.student_id)) order by n.created_at desc,n.id desc limit 21 offset ?",u.getId(),u.getId(),u.getId(),Math.max(0,Math.min(page,100000))*20);}
 @Transactional public void read(Authentication auth,long id){if(jdbc.update("update school_notification set read_at=current_timestamp where id=? and recipient_id=?",id,access.currentUser(auth).getId())==0)throw new AccessDeniedException("Уведомление недоступно.");}
 public List<Map<String,Object>> subgroups(Authentication auth){var u=author(auth);return jdbc.queryForList("select g.id,c.name as class_name,g.name from schedule_subgroup g join schedule_class c on c.id=g.class_id where g.school_id=? and (?='DIRECTOR' or exists(select 1 from teaching_assignment a where a.class_id=c.id and a.teacher_id=?)) order by c.name,g.name",u.getSchool().getId(),u.getRole().name(),u.getTeacherId());}
 public List<Map<String,Object>> lessonChoices(Authentication auth,Long classId){if(classId==null)return List.of();var u=author(auth);assigned(u,classId);return jdbc.queryForList("select l.id,l.lesson_date,l.start_time,s.name from schedule_lesson l join schedule_subject s on s.id=l.subject_id where l.class_id=? and l.school_id=? and l.teacher_id=? order by l.lesson_date desc,l.start_time desc limit 100",classId,u.getSchool().getId(),u.getTeacherId());}
 @Transactional public void attachments(Authentication auth,long id,org.springframework.web.multipart.MultipartFile[] files){
  var row=get(auth,id);if(((Number)row.get("author_id")).longValue()!=access.currentUser(auth).getId())throw new AccessDeniedException("Нет доступа.");
  jdbc.queryForList("select id from schedule_school where id=? for update",row.get("school_id"));
  AcademicYearArchive.requireOpenDate(jdbc,((Number)row.get("school_id")).longValue(),((java.sql.Date)row.get("event_date")).toLocalDate());
  if(files==null)return;var valid=Arrays.stream(files).filter(f->!f.isEmpty()).toList();if(valid.size()>5)throw new ScheduleException("Можно приложить не более пяти файлов.");
  if(jdbc.queryForObject("select count(*) from message_attachment where message_id=?",Long.class,id)>0)return;
  for(var file:valid){if(file.getSize()>10*1024*1024)throw new ScheduleException("Файл превышает 10 МБ.");
   String name=Objects.requireNonNullElse(file.getOriginalFilename(),"file").replaceAll("[\\\\/\\r\\n]","_");if(name.length()>200)name=name.substring(name.length()-200);
   byte[] data;try{data=file.getBytes();}catch(java.io.IOException ex){throw new ScheduleException("Не удалось прочитать файл.");}
   String lower=name.toLowerCase(Locale.ROOT);String type=null;
   if(lower.endsWith(".pdf") && data.length>4 && new String(data,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF"))type="application/pdf";
   if((lower.endsWith(".jpg")||lower.endsWith(".jpeg")) && data.length>2 && (data[0]&255)==255 && (data[1]&255)==216)type="image/jpeg";
   if(lower.endsWith(".png") && data.length>8 && data[0]==(byte)137 && data[1]==80 && data[2]==78 && data[3]==71)type="image/png";
   if(lower.endsWith(".docx")){try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))){java.util.zip.ZipEntry e;int count=0;long expanded=0;byte[] buffer=new byte[8192];while((e=zip.getNextEntry())!=null && ++count<200){if(e.getName().equals("word/document.xml")){type="application/vnd.openxmlformats-officedocument.wordprocessingml.document";break;}int n;while((n=zip.read(buffer))!=-1){expanded+=n;if(expanded>20*1024*1024)throw new ScheduleException("Слишком большой распакованный DOCX.");}}}catch(java.io.IOException ex){throw new ScheduleException("Повреждённый DOCX.");}}
   if(type==null)throw new ScheduleException("Разрешены PDF, JPG, PNG и DOCX с корректным содержимым.");jdbc.update("insert into message_attachment(message_id,name,content_type,data) values(?,?,?,?)",id,name,type,data);
  }
 }
 public List<Map<String,Object>> attachments(Authentication auth,long id){get(auth,id);return jdbc.queryForList("select id,name from message_attachment where message_id=?",id);}
 public Map<String,Object> download(Authentication auth,long id){var rows=jdbc.queryForList("select * from message_attachment where id=?",id);if(rows.isEmpty())throw new AccessDeniedException("Файл недоступен.");get(auth,((Number)rows.getFirst().get("message_id")).longValue());return rows.getFirst();}}
