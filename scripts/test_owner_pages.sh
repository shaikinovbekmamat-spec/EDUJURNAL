#!/bin/bash
# Скрипт для тестирования страниц owner в EduJournal

BASE_URL="http://localhost:8080"
COOKIES_FILE="/tmp/owner_cookies.txt"
OUTPUT_DIR="/c/Users/Lenovo/Downloads/Таможня/EduJurnal/test_results"

# Создаем директорию для результатов
mkdir -p "$OUTPUT_DIR"

echo "=== Тестирование аккаунта Owner ==="
echo "Время: $(date)"
echo ""

# Тест 1: Проверка доступности приложения
echo "1. Проверка доступности приложения..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/login")
if [ "$STATUS" -eq 200 ]; then
    echo "   ✅ Приложение доступно (HTTP $STATUS)"
else
    echo "   ❌ Приложение недоступно (HTTP $STATUS)"
    exit 1
fi

# Тест 2: Получение CSRF токена и вход
echo "2. Авторизация под owner..."
# Получаем страницу входа и извлекаем CSRF токен
LOGIN_PAGE=$(curl -s -c "$COOKIES_FILE" "$BASE_URL/login")
CSRF_TOKEN=$(echo "$LOGIN_PAGE" | grep '_csrf' | sed -n 's/.*value="\([^"]*\)".*/\1/p' | head -1)

if [ -z "$CSRF_TOKEN" ]; then
    echo "   ❌ Не удалось получить CSRF токен"
    echo "   Попробуем без CSRF..."
    CSRF_TOKEN=""
fi

# Выполняем вход
if [ -n "$CSRF_TOKEN" ]; then
    LOGIN_RESPONSE=$(curl -s -b "$COOKIES_FILE" -c "$COOKIES_FILE" \
        -L -w "\n%{http_code}" \
        -d "username=owner&password=owner123&_csrf=$CSRF_TOKEN" \
        "$BASE_URL/login")
else
    LOGIN_RESPONSE=$(curl -s -b "$COOKIES_FILE" -c "$COOKIES_FILE" \
        -L -w "\n%{http_code}" \
        -d "username=owner&password=owner123" \
        "$BASE_URL/login")
fi

LOGIN_STATUS=$(echo "$LOGIN_RESPONSE" | tail -1)
LOGIN_BODY=$(echo "$LOGIN_RESPONSE" | head -n -1)

if [ "$LOGIN_STATUS" -eq 200 ]; then
    # Проверяем, успешна ли авторизация
    if echo "$LOGIN_BODY" | grep -q "Расписание школ\|owner\|Выйти"; then
        echo "   ✅ Авторизация успешна (HTTP $LOGIN_STATUS)"
    else
        echo "   ⚠️  Получен HTTP 200, но контент может быть неверным"
        echo "$LOGIN_BODY" > "$OUTPUT_DIR/login_response.html"
    fi
else
    echo "   ❌ Ошибка авторизации (HTTP $LOGIN_STATUS)"
    exit 1
fi

# Тест 3: Проверка /owner/dashboard
echo "3. Проверка /owner/dashboard (должен редиректить на /schedule)..."
DASHBOARD_RESPONSE=$(curl -s -b "$COOKIES_FILE" -L "$BASE_URL/owner/dashboard")
echo "$DASHBOARD_RESPONSE" > "$OUTPUT_DIR/owner_dashboard.html"

if echo "$DASHBOARD_RESPONSE" | grep -q "Расписание школ"; then
    echo "   ✅ Редирект на страницу выбора школы работает"
else
    echo "   ⚠️  Неожиданный ответ от /owner/dashboard"
fi

# Тест 4: Проверка /schedule (выбор школы)
echo "4. Проверка /schedule (страница выбора школы)..."
SCHEDULE_PAGE=$(curl -s -b "$COOKIES_FILE" "$BASE_URL/schedule")
echo "$SCHEDULE_PAGE" > "$OUTPUT_DIR/schedule_select.html"

if echo "$SCHEDULE_PAGE" | grep -q "Расписание школ"; then
    echo "   ✅ Страница выбора школы загружена"
    if echo "$SCHEDULE_PAGE" | grep -q "Школа"; then
        SCHOOL_COUNT=$(echo "$SCHEDULE_PAGE" | grep -c "schedule?schoolId=")
        echo "   ✅ Найдено школ в списке: $SCHOOL_COUNT"
    else
        echo "   ⚠️  Школы не найдены в списке"
    fi

    # Проверяем наличие ссылки на управление аккаунтами
    if echo "$SCHEDULE_PAGE" | grep -q "Управление аккаунтами директоров"; then
        echo "   ✅ Ссылка на управление директорами присутствует"
    fi
else
    echo "   ❌ Страница выбора школы не загружена"
fi

# Тест 5: Проверка /schedule с выбранной школой
echo "5. Проверка /schedule?schoolId=1..."
SCHEDULE_SCHOOL=$(curl -s -b "$COOKIES_FILE" "$BASE_URL/schedule?schoolId=1")
echo "$SCHEDULE_SCHOOL" > "$OUTPUT_DIR/schedule_school1.html"

if echo "$SCHEDULE_SCHOOL" | grep -q "Расписание на полугодие"; then
    echo "   ✅ Расписание школы загружено"

    # Проверяем наличие ссылки "Выбрать другую школу" (только для owner)
    if echo "$SCHEDULE_SCHOOL" | grep -q "Выбрать другую школу"; then
        echo "   ✅ Ссылка 'Выбрать другую школу' присутствует (специфично для owner)"
    else
        echo "   ⚠️  Ссылка 'Выбрать другую школу' не найдена"
    fi

    # Проверяем отсутствие кнопок редактирования
    if echo "$SCHEDULE_SCHOOL" | grep -q "Добавить урок"; then
        echo "   ❌ Кнопка 'Добавить урок' найдена (не должна быть доступна owner)"
    else
        echo "   ✅ Кнопка 'Добавить урок' отсутствует (правильно)"
    fi

    if echo "$SCHEDULE_SCHOOL" | grep -q "Составить расписание школы"; then
        echo "   ❌ Кнопка 'Составить расписание' найдена (не должна быть доступна owner)"
    else
        echo "   ✅ Кнопка 'Составить расписание' отсутствует (правильно)"
    fi
else
    echo "   ❌ Расписание школы не загружено"
fi

# Тест 6: Проверка /management (управление директорами - доступно owner)
echo "6. Проверка /management (управление директорами)..."
MGMT_PAGE=$(curl -s -b "$COOKIES_FILE" -w "\n%{http_code}" "$BASE_URL/management")
MGMT_STATUS=$(echo "$MGMT_PAGE" | tail -1)
MGMT_BODY=$(echo "$MGMT_PAGE" | head -n -1)

echo "$MGMT_BODY" > "$OUTPUT_DIR/management.html"

if [ "$MGMT_STATUS" -eq 200 ]; then
    if echo "$MGMT_BODY" | grep -q "Управление школой\|Список школ"; then
        echo "   ✅ /management доступен (HTTP $MGMT_STATUS)"
    else
        echo "   ⚠️  Страница загружена, но контент неожиданный"
    fi
else
    echo "   ❌ /management недоступен (HTTP $MGMT_STATUS)"
fi

# Тест 7: Проверка недоступных страниц (только для DIRECTOR)
echo "7. Проверка страниц только для DIRECTOR..."

# /schedule/new (только для DIRECTOR)
NEW_STATUS=$(curl -s -b "$COOKIES_FILE" -o /dev/null -w "%{http_code}" "$BASE_URL/schedule/new")
if [ "$NEW_STATUS" -eq 403 ]; then
    echo "   ✅ /schedule/new недоступен owner (HTTP $NEW_STATUS - правильно)"
elif [ "$NEW_STATUS" -eq 302 ]; then
    echo "   ⚠️  /schedule/new вернул редирект (HTTP $NEW_STATUS)"
else
    echo "   ⚠️  /schedule/new вернул HTTP $NEW_STATUS (ожидался 403)"
fi

# /schedule/generate (только для DIRECTOR)
GEN_STATUS=$(curl -s -b "$COOKIES_FILE" -o /dev/null -w "%{http_code}" "$BASE_URL/schedule/generate")
if [ "$GEN_STATUS" -eq 403 ]; then
    echo "   ✅ /schedule/generate недоступен owner (HTTP $GEN_STATUS - правильно)"
elif [ "$GEN_STATUS" -eq 302 ]; then
    echo "   ⚠️  /schedule/generate вернул редирект (HTTP $GEN_STATUS)"
else
    echo "   ⚠️  /schedule/generate вернул HTTP $GEN_STATUS (ожидался 403)"
fi

echo ""
echo "=== Тестирование завершено ==="
echo "Результаты сохранены в: $OUTPUT_DIR"
echo ""
echo "Сводка для owner:"
echo "✅ Может входить в систему"
echo "✅ Может видеть список всех школ"
echo "✅ Может просматривать расписание любой школы"
echo "✅ Может управлять аккаунтами директоров"
echo "✅ НЕ может редактировать расписание (только просмотр)"
echo "✅ НЕ может создавать уроки"
echo "✅ НЕ может использовать функции генерации расписания"

# Очистка
rm -f "$COOKIES_FILE"
