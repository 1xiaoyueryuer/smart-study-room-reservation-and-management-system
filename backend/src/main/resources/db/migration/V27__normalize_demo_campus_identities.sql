-- Only migrate the exact built-in teaching-demo identities; never invent IDs for other users.
WITH candidates AS (
    SELECT id, username, campus_id, real_name, role, status,
           '2026' || '0' || lpad(n::text, 4, '0') AS normalized_id
    FROM users
    CROSS JOIN generate_series(1, 40) AS series(n)
    WHERE username = 'stu' || lpad(n::text, 2, '0')
      AND campus_id = 'DEMO' || lpad(n::text, 4, '0')
      AND real_name = '模拟学生' || n::text
      AND role = 'student' AND status = 'active'
)
INSERT INTO audit_events(actor_id, action, object_type, object_id, reason, before_values, after_values, occurred_at)
SELECT NULL, 'user.identity.normalize', 'user', id::text,
       '将内置模拟学生账号统一为9位校园编号与同号登录名',
       jsonb_build_object('username', username, 'campus_id', campus_id),
       jsonb_build_object('username', normalized_id, 'campus_id', normalized_id), CURRENT_TIMESTAMP
FROM candidates;

WITH candidates AS (
    SELECT id, '2026' || '0' || lpad(n::text, 4, '0') AS normalized_id
    FROM users
    CROSS JOIN generate_series(1, 40) AS series(n)
    WHERE username = 'stu' || lpad(n::text, 2, '0')
      AND campus_id = 'DEMO' || lpad(n::text, 4, '0')
      AND real_name = '模拟学生' || n::text
      AND role = 'student' AND status = 'active'
)
UPDATE users u SET username = candidates.normalized_id, campus_id = candidates.normalized_id,
                   updated_at = CURRENT_TIMESTAMP
FROM candidates WHERE u.id = candidates.id;

-- Normalize only the exact built-in teaching-demo administrator; this is synthetic sample data.
INSERT INTO audit_events(actor_id, action, object_type, object_id, reason, before_values, after_values, occurred_at)
SELECT NULL, 'user.identity.normalize', 'user', id::text,
       '将内置模拟管理员账号统一为9位校园编号与同号登录名',
       jsonb_build_object('username', username, 'campus_id', campus_id),
       jsonb_build_object('username', '202630001', 'campus_id', '202630001'), CURRENT_TIMESTAMP
FROM users
WHERE username = 'admin' AND real_name = '管理员' AND role = 'admin' AND campus_id IS NULL;

UPDATE users SET username = '202630001', campus_id = '202630001', updated_at = CURRENT_TIMESTAMP
WHERE username = 'admin' AND real_name = '管理员' AND role = 'admin' AND campus_id IS NULL;
