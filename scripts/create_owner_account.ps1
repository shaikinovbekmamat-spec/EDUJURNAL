# PowerShell скрипт для создания owner аккаунта в EduJournal
# Использует psql для выполнения SQL команд

$env:PGPASSWORD = "5555"
$dbHost = "localhost"
$dbPort = "5432"
$dbName = "tkrpo"
$dbUser = "postgres"

# SQL команда для создания owner аккаунта
$sql = @"
-- Проверяем существующие owner аккаунты
SELECT id, login, role, full_name FROM users WHERE role = 'OWNER';

-- Создаем owner аккаунт если его нет
INSERT INTO users (login, password, role, enabled, full_name, school_id)
VALUES ('owner', '$2a$10$N9qo8uLOickgx2ZMRZoMye1JdJAXmLKT.pqJBu1xOyQBmLiRKpWaO', 'OWNER', true, 'Владелец системы', NULL)
ON CONFLICT (login) DO UPDATE SET
  password = EXCLUDED.password,
  role = EXCLUDED.role,
  full_name = EXCLUDED.full_name;

-- Показываем результат
SELECT id, login, role, full_name, enabled, school_id FROM users WHERE role = 'OWNER';
"@

Write-Host "Создание owner аккаунта в базе данных EduJournal..."
Write-Host "Логин: owner"
Write-Host "Пароль: owner123"
Write-Host ""

# Сохраняем SQL во временный файл
$tempFile = [System.IO.Path]::GetTempFileName() + ".sql"
$sql | Out-File -FilePath $tempFile -Encoding UTF8

# Выполняем SQL через psql
try {
    & "C:\Program Files\PostgreSQL\16\bin\psql.exe" -h $dbHost -p $dbPort -U $dbUser -d $dbName -f $tempFile
} catch {
    Write-Host "Ошибка: psql не найден. Попробуйте установить PostgreSQL или укажите правильный путь."
    Write-Host "Или выполните SQL команды вручную:"
    Write-Host $sql
} finally {
    # Удаляем временный файл
    if (Test-Path $tempFile) {
        Remove-Item $tempFile
    }
}
