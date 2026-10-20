insert into schedule_school values (1, 'Демонстрационная школа');
insert into schedule_class values (1, 1, '7А'), (2, 1, '7Б');
insert into schedule_subgroup values (1, 1, 1, 'Группа 1'), (2, 1, 1, 'Группа 2'), (3, 1, 1, 'Английский — продолжающие');
insert into schedule_membership values
    (1, 101, '2026-09-01', null), (1, 102, '2026-09-01', null),
    (2, 103, '2026-09-01', null), (2, 104, '2026-09-01', null),
    (3, 101, '2026-09-01', null), (3, 103, '2026-09-01', null);
insert into schedule_subject values (1, 1, 'Математика'), (2, 1, 'Кыргызский язык'), (3, 1, 'Английский язык');
insert into schedule_teacher values (1, 1, 'Иванова Анна Сергеевна'), (2, 1, 'Асанов Бакыт'), (3, 1, 'Петрова Елена');
insert into schedule_room values (1, 1, '101'), (2, 1, '102'), (3, 1, '201');
