-- The built-in demo administrator keeps the documented login username 202630001,
-- but must not be assigned a fabricated student/staff campus identity.
WITH candidates AS (
    SELECT u.id, u.username, u.campus_id
    FROM users u
    WHERE u.username='202630001' AND u.campus_id='202630001'
      AND u.real_name='管理员' AND u.role='admin'
      AND EXISTS (
          SELECT 1 FROM audit_events a
          WHERE a.action='user.identity.normalize'
            AND a.object_type='user' AND a.object_id=u.id::text
            AND a.before_values->>'username'='admin'
            AND a.after_values->>'username'='202630001'
      )
)
INSERT INTO audit_events(actor_id,action,object_type,object_id,reason,before_values,after_values,occurred_at)
SELECT NULL,'user.identity.normalize','user',id::text,
       '保留内置模拟管理员登录名202630001并移除误填校园编号',
       jsonb_build_object('username',username,'campus_id',campus_id),
       jsonb_build_object('username','202630001','campus_id',NULL),CURRENT_TIMESTAMP
FROM candidates;

WITH candidates AS (
    SELECT u.id
    FROM users u
    WHERE u.username='202630001' AND u.campus_id='202630001'
      AND u.real_name='管理员' AND u.role='admin'
      AND EXISTS (
          SELECT 1 FROM audit_events a
          WHERE a.action='user.identity.normalize'
            AND a.object_type='user' AND a.object_id=u.id::text
            AND a.before_values->>'username'='admin'
            AND a.after_values->>'username'='202630001'
      )
)
UPDATE users u SET campus_id=NULL,updated_at=CURRENT_TIMESTAMP
FROM candidates c WHERE u.id=c.id;
