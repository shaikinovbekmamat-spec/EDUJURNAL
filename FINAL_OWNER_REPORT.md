# ✅ Отчет: Создание и настройка аккаунта Owner

**Дата:** 2026-11-15  
**Задача:** Создать аккаунт owner и настроить его функционал

---

## ✅ Выполнено

### 1. Создан аккаунт owner в базе данных

```sql
INSERT INTO users (login, password, role, enabled, full_name, school_id)
VALUES ('owner', '$2a$10$N9qo8uLOickgx2ZMRZoMye1JdJAXmLKT.pqJBu1xOyQBmLiRKpWaO', 
        'OWNER', true, 'Owner System', NULL);
```

**Учетные данные:**
- **URL:** http://localhost:8080/login
- **Логин:** `owner`
- **Пароль:** `owner123`
- **ID:** 7
- **Роль:** OWNER
- **School ID:** NULL ✓

### 2. Исправлен редирект owner dashboard

**Было:** `/owner/dashboard` → `/schedule` (просмотр расписания)  
**Стало:** `/owner/dashboard` → `/management` (управление системой)

**Файл:** `src/main/java/com/example/edujurnal/Controller/OwnerController.java`

### 3. Добавлена функция создания школ

**Добавлен метод в AcademicService:**
```java
@Transactional
public School createSchool(Authentication auth, String name) {
    User actor = access.currentUser(auth);
    if (actor.getRole() != Role.OWNER) 
        throw new AccessDeniedException("Только владелец может создавать школы.");
    if (name == null || name.trim().isEmpty()) 
        throw new ScheduleException("Введите название школы.");
    School school = new School();
    school.setName(name.trim());
    return entityManager.merge(school);
}
```

**Добавлен endpoint в AcademicController:**
```java
@PreAuthorize("hasRole('OWNER')")
@PostMapping("/management/schools")
public String createSchool(Authentication auth, @RequestParam String name, 
                          RedirectAttributes flash) {
    service.createSchool(auth,name);
    flash.addFlashAttribute("success","Школа создана.");
    return "redirect:/management";
}
```

### 4. Обновлен интерфейс owner

**Файл:** `src/main/resources/templates/academic/schools.html`

**Добавлено:**
- ✅ Форма создания новой школы
- ✅ Таблица со списком всех школ
- ✅ Кнопки управления директорами для каждой школы
- ✅ Раздел с инструкциями по резервному копированию
- ✅ Примеры команд PowerShell для бэкапов

---

## 🎯 Функционал Owner

### Что МОЖЕТ owner:

✅ **Управление школами:**
   - Создавать новые школы
   - Просматривать список всех школ в системе

✅ **Управление директорами:**
   - Выбирать школу
   - Создавать аккаунты директоров для любой школы
   - Назначать директора на конкретную школу

✅ **Резервное копирование:**
   - Доступ к инструкциям по бэкапу
   - Информация о скриптах: `backup.ps1`, `restore.ps1`, `verify-backup.ps1`
   - Команды для выполнения резервного копирования

### Что НЕ МОЖЕТ owner:

❌ Просматривать расписание (это функция директора/учителя/ученика)
❌ Создавать классы/предметы/учителей (это делает директор)
❌ Редактировать уроки
❌ Выставлять оценки
❌ Работать с журналом

---

## 📋 Страницы Owner

### 1. `/owner/dashboard`
- Автоматически перенаправляет на `/management`

### 2. `/management` (без schoolId)
- **Главная страница owner**
- Форма создания новой школы
- Список всех школ с кнопками управления
- Инструкции по резервному копированию
- Примеры команд PowerShell

### 3. `/management?schoolId=X`
- Управление аккаунтами директоров конкретной школы
- Создание директора для выбранной школы
- Owner может создавать только директоров (не учителей/учеников)

---

## 🔧 Изменения в коде

### Измененные файлы:
1. `src/main/java/com/example/edujurnal/Controller/OwnerController.java`
   - Изменен редирект: `/schedule` → `/management`

2. `src/main/java/com/example/edujurnal/Service/AcademicService.java`
   - Добавлен метод `createSchool()` для создания школ

3. `src/main/java/com/example/edujurnal/Controller/AcademicController.java`
   - Добавлен endpoint `POST /management/schools` для создания школ

4. `src/main/resources/templates/academic/schools.html`
   - Полностью переработан интерфейс
   - Добавлена форма создания школы
   - Добавлена таблица школ
   - Добавлен раздел резервного копирования

### Новые файлы:
- `scripts/create_owner.sql` - SQL скрипт для создания owner
- `scripts/create_owner_utf8.sql` - UTF-8 версия
- `scripts/create_owner_account.ps1` - PowerShell скрипт
- `scripts/test_owner_pages.sh` - Тесты страниц owner
- `OWNER_TEST_CHECKLIST.md` - Чеклист проверки
- `OWNER_TESTING_GUIDE.md` - Руководство по тестированию
- `OWNER_ACCOUNT_REPORT.md` - Полный отчет

---

## 🧪 Как проверить

### Шаг 1: Войти в систему
1. Откройте: http://localhost:8080/login
2. Логин: `owner`
3. Пароль: `owner123`
4. После входа → автоматический редирект на `/management`

### Шаг 2: Создать школу
1. На главной странице заполните форму "Создать новую школу"
2. Введите название (например: "Школа №2")
3. Нажмите "Создать школу"
4. Школа появится в таблице ниже

### Шаг 3: Создать директора
1. Нажмите "Управление директорами" для выбранной школы
2. Создайте аккаунт с ролью DIRECTOR
3. Укажите логин (цифры), пароль, ФИО
4. Директор будет привязан к этой школе

### Шаг 4: Резервное копирование
1. Прокрутите страницу вниз до раздела "Резервное копирование"
2. Скопируйте команды PowerShell
3. Выполните в PowerShell для создания бэкапа

---

## 📊 Резервное копирование

### Существующие скрипты:
- `scripts/backup.ps1` - создание резервной копии
- `scripts/restore.ps1` - восстановление из резервной копии
- `scripts/verify-backup.ps1` - проверка резервной копии
- `scripts/backup-common.ps1` - общие функции

### Документация:
- `docs/archive-and-recovery.md` - полная инструкция

### Пример создания бэкапа:
```powershell
$env:PG_BIN = 'C:\Program Files\PostgreSQL\17\bin'
$env:PGHOST = '127.0.0.1'
$env:PGPORT = '5432'
$env:PGUSER = 'postgres'
$env:PGPASSWORD = '5555'

cd "C:\Users\Lenovo\Downloads\Таможня\EduJurnal"
.\scripts\backup.ps1 -Database tkrpo -OutputDirectory '.\backups' -Prune
```

---

## ✅ Итого

**Аккаунт owner полностью настроен и готов к использованию!**

Функционал соответствует требованиям:
1. ✅ Управление школами (создание)
2. ✅ Управление директорами (создание аккаунтов)
3. ✅ Резервное копирование (инструкции и скрипты)

**Owner НЕ просматривает расписание** - это правильно, так как его задача управлять системой на верхнем уровне.

---

## 🚀 Следующие шаги

Откройте браузер и проверьте все функции owner:
- Создание школ
- Создание директоров
- Доступ к инструкциям по бэкапу

**URL для входа:** http://localhost:8080/login  
**Логин:** owner  
**Пароль:** owner123
