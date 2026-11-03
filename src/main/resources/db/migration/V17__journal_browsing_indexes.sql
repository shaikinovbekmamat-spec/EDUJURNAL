create index users_class_name on users(student_class_id,role,full_name,id);
create index attendance_student_lesson on attendance(student_id,lesson_id);
create index notification_recipient_read on attendance_notification(recipient_id,read_at,student_id);