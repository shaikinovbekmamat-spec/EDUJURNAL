-- Создание аккаунта owner
-- Логин: owner
-- Пароль: owner123
-- BCrypt хеш для пароля "owner123": $2a$10$N9qo8uLOickgx2ZMRZoMye1JdJAXmLKT.pqJBu1xOyQBmLiRKpWaO

INSERT INTO users (login, password, role, enabled, full_name, school_id)
VALUES ('owner', '$2a$10$N9qo8uLOickgx2ZMRZoMye1JdJAXmLKT.pqJBu1xOyQBmLiRKpWaO', 'OWNER', true, 'Владелец системы', NULL)
ON CONFLICT (login) DO NOTHING;

-- Проверка созданного аккаунта
SELECT id, login, role, full_name, enabled, school_id FROM users WHERE role = 'OWNER';
