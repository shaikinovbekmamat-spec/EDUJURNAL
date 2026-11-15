-- Create owner account
-- Login: owner
-- Password: owner123

INSERT INTO users (login, password, role, enabled, full_name, school_id)
VALUES ('owner', '$2a$10$N9qo8uLOickgx2ZMRZoMye1JdJAXmLKT.pqJBu1xOyQBmLiRKpWaO', 'OWNER', true, 'Owner System', NULL);

SELECT id, login, role, full_name, enabled FROM users WHERE role = 'OWNER';
