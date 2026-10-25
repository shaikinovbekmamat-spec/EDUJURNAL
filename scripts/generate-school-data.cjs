// Generates fictional school data as an explicit PostgreSQL transaction.
// Run: node scripts/generate-school-data.cjs
// Then: psql ... -v ON_ERROR_STOP=1 -v school_id=1 -f target/school-showcase.sql
const fs = require('fs');
const crypto = require('crypto');
const names = [
 'Абдылдаева Айгуль Нурлановна','Иванова Елена Сергеевна','Садыков Бакыт Азаматович','Петров Дмитрий Андреевич',
 'Осмонова Гульнара Токтосуновна','Токтобаев Нурбек Эркинович','Мамбетова Алина Кубатовна',
 'Смирнова Наталья Викторовна','Кузнецов Алексей Павлович','Абдрахманова Динара Улановна',
 'Асанова Аида Болотбековна','Ким Анна Олеговна','Муратов Тимур Русланович',
 'Ибраимова Чолпон Бакытовна','Соколова Ольга Михайловна','Жумабаев Эрлан Аскарович','Романова Мария Игоревна',
 'Исакова Назгуль Талантовна','Васильев Андрей Николаевич','Турсунов Адилет Кубанычбекович','Фёдорова Ирина Петровна',
 'Султанова Жылдыз Омурбековна','Беков Данияр Саматович','Алексеев Сергей Валерьевич','Орозов Мирлан Эсенович',
 'Ташматова Бегайым Азизовна','Павлова Вера Александровна','Кадырова Асель Медеровна','Сейитов Нурлан Бакытович'
];
const staffCounts={math:4,kg:3,ru:3,en:3,history:2,bio:2,geo:2,physics:2,chem:1,it:1,pe:2,tech:1,art:1,music:1,social:1};
const staff={}; let staffIndex=0;
for(const [group,count] of Object.entries(staffCounts)) staff[group]=Array.from({length:count},()=>names[staffIndex++]);
const subjects={
 math:['Математика','math'],algebra:['Алгебра','math'],geometry:['Геометрия','math'],
 kg:['Кыргызский язык','kg'],ru:['Русский язык','ru'],en:['Английский язык','en'],
 history:['История','history'],natural:['Естествознание','bio'],geo:['География','geo'],
 pe:['Физическая культура','pe'],music:['Музыка','music'],art:['Изобразительное искусство','art'],
 tech:['Технология','tech'],litru:['Русская литература','ru'],litkg:['Кыргызская литература','kg'],
 bio:['Биология','bio'],it:['Информатика','it'],physics:['Физика','physics'],chem:['Химия','chem'],social:['Человек и общество','social']
};
const curricula={
 5:{math:5,kg:4,ru:3,en:3,history:2,natural:2,geo:1,pe:2,music:1,art:1,tech:2,litru:1},
 6:{math:5,kg:4,ru:3,en:3,history:2,bio:2,geo:2,pe:2,it:1,music:1,art:1,tech:2},
 7:{algebra:3,geometry:2,kg:3,ru:3,en:3,history:2,bio:2,geo:2,physics:2,it:1,pe:2,tech:2,art:1,music:1,social:1},
 8:{algebra:3,geometry:2,kg:3,ru:3,en:3,history:2,bio:2,geo:2,physics:2,chem:2,it:1,pe:2,social:1,tech:2},
 9:{algebra:3,geometry:2,kg:3,ru:3,en:3,history:2,bio:2,geo:2,physics:2,chem:2,it:1,pe:2,social:1,litkg:1,litru:1},
 10:{algebra:4,geometry:2,kg:2,ru:2,en:3,history:2,bio:2,geo:1,physics:3,chem:2,it:2,pe:2,social:1,litkg:1,litru:1},
 11:{algebra:4,geometry:2,kg:2,ru:2,en:3,history:2,bio:2,geo:1,physics:3,chem:2,it:2,pe:2,social:1,litkg:1,litru:1}
};
const classSizes=[30,28,32,29,31,30,28,27,30,29,26,25,24,23];
const homerooms=Array.from({length:14},(_,i)=>`${i<7?1:2}0${i%7+1}`);
const rooms=[...homerooms.map(name=>({name,capacity:36})),
 ...['301 · физика','302 · химия','303 · биология','304 · информатика','305 · информатика','306 · технология'].map(name=>({name,capacity:32})),
 {name:'Спортзал 1',capacity:60},{name:'Спортзал 2',capacity:60}];
const classes=[];
for(let grade=5;grade<=11;grade++) for(const letter of ['А','Б']) {
 const index=classes.length;
 const loads=grade===5?[6,5,6,5,5]:grade===6?[6,6,5,6,5]:[6,6,6,6,6];
 const cells=[];
 for(let day=0;day<5;day++) for(let slot=0;slot<loads[day];slot++) cells.push(day*6+slot);
 const entries=[];
 for(const [key,count] of Object.entries(curricula[grade])) {
   const [subject,group]=subjects[key];
   const teachers=staff[group], teacher=teachers[index%teachers.length];
   let room=homerooms[index];
   if(group==='physics')room='301 · физика';
   if(group==='chem')room='302 · химия';
   if(group==='bio'&&index%2===0)room='303 · биология';
   if(group==='it')room=`${index%2===0?'304':'305'} · информатика`;
   if(group==='tech')room='306 · технология';
   if(group==='pe')room=`Спортзал ${index%2+1}`;
   for(let n=0;n<count;n++)entries.push({subject,group,teacher,room});
 }
 if(entries.length!==cells.length)throw Error(`Curriculum count for grade ${grade}: ${entries.length}`);
 classes.push({name:`${grade}${letter}`,students:classSizes[index],cells,entries});
}
// Swaps preserve each class's curriculum and contiguous daily lesson counts.
let rng=20261025;
function random(){rng^=rng<<13;rng^=rng>>>17;rng^=rng<<5;return (rng>>>0)/4294967296;}
function shuffle(items){for(let i=items.length-1;i>0;i--){const j=Math.floor(random()*(i+1));[items[i],items[j]]=[items[j],items[i]];}return items;}
let grid;
function slotScore(slot){
 const teachers=new Map(),occupied=new Map();let score=0;
 for(const row of grid){const item=row[slot];if(!item)continue;
   for(const [map,key] of [[teachers,item.teacher],[occupied,item.room]]){const count=map.get(key)||0;score+=count*10;map.set(key,count+1);}
 }return score;
}
function dayScore(c,day){
 const groups={};let score=0;
 for(let slot=day*6;slot<day*6+6;slot++){const item=grid[c][slot];if(item)groups[item.group]=(groups[item.group]||0)+1;}
 for(const [group,count] of Object.entries(groups)){const excess=Math.max(0,count-(group==='math'?2:1));score+=excess*(excess+1)*2;}
 return score;
}
let solved=false, attempts=0;
for(;attempts<12&&!solved;attempts++){
 grid=classes.map(c=>{const row=Array(30).fill(null),entries=shuffle([...c.entries]);c.cells.forEach((slot,i)=>row[slot]=entries[i]);return row;});
 let energy=Array.from({length:30},(_,s)=>slotScore(s)).reduce((a,b)=>a+b,0)
   +classes.reduce((sum,c,i)=>sum+Array.from({length:5},(_,d)=>dayScore(i,d)).reduce((a,b)=>a+b,0),0);
 for(let step=0;step<450000&&energy>0;step++){
   const c=Math.floor(random()*classes.length),cells=classes[c].cells;
   const a=cells[Math.floor(random()*cells.length)],b=cells[Math.floor(random()*cells.length)];
   if(a===b||grid[c][a].subject===grid[c][b].subject)continue;
   const days=[...new Set([Math.floor(a/6),Math.floor(b/6)])];
   const before=slotScore(a)+slotScore(b)+days.reduce((sum,d)=>sum+dayScore(c,d),0);
   [grid[c][a],grid[c][b]]=[grid[c][b],grid[c][a]];
   const after=slotScore(a)+slotScore(b)+days.reduce((sum,d)=>sum+dayScore(c,d),0);
   const delta=after-before;
   const temperature=4*Math.pow(0.003, (step%150000)/150000);
   if(delta<=0||random()<Math.exp(-delta/temperature))energy+=delta;
   else [grid[c][a],grid[c][b]]=[grid[c][b],grid[c][a]];
 }
 solved=energy===0;
}
if(!solved)throw Error('Could not find a conflict-free timetable; no SQL was generated.');
const quote=value=>`'${String(value).replaceAll("'","''")}'`;
const values=rows=>rows.map(row=>'('+row.map(v=>typeof v==='number'?v:quote(v)).join(',')+')').join(',\n');
const qualifications=Object.entries(staff).flatMap(([group,teachers])=>teachers.flatMap(teacher=>
    Object.values(subjects).filter(s=>s[1]===group).map(s=>[teacher,s[0]])));
const uuid=key=>{const h=crypto.createHash('md5').update(key).digest('hex');return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;};
const pattern=[];
classes.forEach((c,i)=>c.cells.forEach(slot=>{
 const item=grid[i][slot];
 pattern.push([c.name,item.subject,item.teacher,item.room,Math.floor(slot/6)+1,slot%6+1,uuid(`school-showcase-v1:${c.name}:${slot}`)]);
}));
let sql=`\\set ON_ERROR_STOP on
\\if :{?school_id}
\\else
\\set school_id 1
\\endif
BEGIN;
SET LOCAL TIME ZONE 'Asia/Bishkek';
SELECT id FROM schedule_school WHERE id = :school_id FOR UPDATE;
LOCK TABLE schedule_class, schedule_subject, schedule_teacher, schedule_room IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE seed_meta ON COMMIT DROP AS
SELECT :school_id::bigint school_id,
       make_date(extract(year from current_date)::int, CASE WHEN extract(month from current_date)<=5 THEN 1 ELSE 9 END,1) starts,
       CASE WHEN extract(month from current_date)<=5 THEN make_date(extract(year from current_date)::int,5,31)
            ELSE make_date(extract(year from current_date)::int,12,31) END ends;
DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM schedule_school s JOIN seed_meta m ON s.id=m.school_id)
THEN RAISE EXCEPTION 'Target school does not exist'; END IF; END $$;
CREATE TEMP TABLE seed_class(name text, student_count int) ON COMMIT DROP;
INSERT INTO seed_class VALUES ${values(classes.map(c=>[c.name,c.students]))};
CREATE TEMP TABLE seed_subject(name text) ON COMMIT DROP;
INSERT INTO seed_subject VALUES ${values(Object.values(subjects).map(s=>[s[0]]))};
CREATE TEMP TABLE seed_teacher(name text) ON COMMIT DROP;
INSERT INTO seed_teacher VALUES ${values(names.map(n=>[n]))};
CREATE TEMP TABLE seed_room(name text, capacity int) ON COMMIT DROP;
INSERT INTO seed_room VALUES ${values(rooms.map(r=>[r.name,r.capacity]))};
`;
for(const type of ['class','subject','teacher','room']){
 const extra=type==='class'?',student_count':type==='room'?',capacity':'';
 sql+=`INSERT INTO schedule_${type}(id,school_id,name${extra})
SELECT (SELECT coalesce(max(id),0) FROM schedule_${type}) + row_number() OVER(ORDER BY x.name),m.school_id,x.name${extra?`,x.${extra.slice(1)}`:''}
FROM seed_${type} x CROSS JOIN seed_meta m
WHERE NOT EXISTS(SELECT 1 FROM schedule_${type} e WHERE e.school_id=m.school_id AND e.name=x.name);
`;
}
sql+=`INSERT INTO schedule_teacher_subject(school_id,teacher_id,subject_id)
SELECT m.school_id,t.id,s.id FROM (VALUES ${values(qualifications)}) q(teacher_name,subject_name)
CROSS JOIN seed_meta m JOIN schedule_teacher t ON t.school_id=m.school_id AND t.name=q.teacher_name
JOIN schedule_subject s ON s.school_id=m.school_id AND s.name=q.subject_name
ON CONFLICT DO NOTHING;
CREATE TEMP TABLE seed_pattern(class_name text, subject_name text, teacher_name text, room_name text, weekday int, slot int, series_id text) ON COMMIT DROP;
INSERT INTO seed_pattern VALUES ${values(pattern)};
CREATE TEMP TABLE seed_candidates ON COMMIT DROP AS
SELECT m.school_id,c.id class_id,s.id subject_id,t.id teacher_id,r.id room_id,d.day::date lesson_date,
       b.starts start_time,b.ends end_time,
       md5('school-showcase-v1:'||m.school_id||':'||p.class_name||':'||p.weekday||':'||p.slot||':'||d.day::date)::uuid::text request_token,
       md5(m.school_id||':'||m.starts||':'||p.series_id)::uuid::text series_id
FROM seed_pattern p CROSS JOIN seed_meta m
JOIN schedule_class c ON c.school_id=m.school_id AND c.name=p.class_name
JOIN schedule_subject s ON s.school_id=m.school_id AND s.name=p.subject_name
JOIN schedule_teacher t ON t.school_id=m.school_id AND t.name=p.teacher_name
JOIN schedule_room r ON r.school_id=m.school_id AND r.name=p.room_name
CROSS JOIN LATERAL generate_series(m.starts::timestamp,m.ends::timestamp,interval '1 day') d(day)
JOIN (VALUES (1,time '08:00',time '08:45'),(2,time '08:50',time '09:35'),(3,time '09:40',time '10:25'),
             (4,time '10:40',time '11:25'),(5,time '11:30',time '12:15'),(6,time '12:20',time '13:05')) b(slot,starts,ends) ON b.slot=p.slot
WHERE extract(isodow from d.day)=p.weekday
AND NOT EXISTS(SELECT 1 FROM schedule_lesson e WHERE e.request_token=md5('school-showcase-v1:'||m.school_id||':'||p.class_name||':'||p.weekday||':'||p.slot||':'||d.day::date)::uuid::text);
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM seed_candidates n JOIN schedule_lesson e ON e.school_id=n.school_id AND e.lesson_date=n.lesson_date
   AND e.start_time<n.end_time AND n.start_time<e.end_time
   AND (e.class_id=n.class_id OR e.teacher_id=n.teacher_id OR e.room_id=n.room_id))
 THEN RAISE EXCEPTION 'Existing lessons conflict with proposed data. Transaction rolled back; existing data is preserved.'; END IF;
 IF EXISTS(SELECT 1 FROM seed_candidates a JOIN seed_candidates b ON a.request_token<b.request_token
   AND a.lesson_date=b.lesson_date AND a.start_time<b.end_time AND b.start_time<a.end_time
   AND (a.class_id=b.class_id OR a.teacher_id=b.teacher_id OR a.room_id=b.room_id))
 THEN RAISE EXCEPTION 'Generated lessons conflict'; END IF;
 IF EXISTS(SELECT 1 FROM seed_candidates n JOIN schedule_class c ON c.id=n.class_id JOIN schedule_room r ON r.id=n.room_id WHERE c.student_count>r.capacity)
 THEN RAISE EXCEPTION 'Room capacity is insufficient'; END IF;
END $$;
WITH inserted AS (
 INSERT INTO schedule_lesson(school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token,series_id)
 SELECT school_id,class_id,subject_id,teacher_id,room_id,lesson_date,start_time,end_time,request_token,series_id FROM seed_candidates
 RETURNING id
) SELECT count(*) AS added_lessons FROM inserted;
SELECT starts,ends FROM seed_meta;
COMMIT;
`;
fs.mkdirSync('target',{recursive:true});
fs.writeFileSync('target/school-showcase.sql',sql);
fs.writeFileSync('target/teacher-specializations.sql',`CREATE TEMP TABLE expected_teacher_subject ON COMMIT DROP AS
SELECT t.school_id,t.id teacher_id,s.id subject_id
FROM (VALUES ${values(qualifications)}) q(teacher_name,subject_name)
JOIN schedule_teacher t ON t.school_id=:school_id AND t.name=q.teacher_name
JOIN schedule_subject s ON s.school_id=t.school_id AND s.name=q.subject_name;
`);
console.log(JSON.stringify({classes:classes.length,students:classSizes.reduce((a,b)=>a+b,0),teachers:names.length,rooms:rooms.length,subjects:Object.keys(subjects).length,lessonsPerCycle:pattern.length,solverAttempts:attempts,output:'target/school-showcase.sql'}));
