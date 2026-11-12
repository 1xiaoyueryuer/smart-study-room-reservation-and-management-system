-- Restore the selected 9-digit demo identity after V28 cleared the campus_id
-- of the exact administrator record normalized by V27. Keep the username unchanged.
WITH candidates AS (
    SELECT u.id,u.username,u.campus_id
    FROM users u
    WHERE u.username='202630001' AND u.campus_id IS NULL
      AND u.real_name='管理员' AND u.role='admin'
      AND EXISTS (
          SELECT 1 FROM audit_events a
          WHERE a.action='user.identity.normalize'
            AND a.object_type='user' AND a.object_id=u.id::text
            AND a.before_values->>'username'='admin'
            AND a.after_values->>'username'='202630001'
      )
      AND EXISTS (
          SELECT 1 FROM audit_events a
          WHERE a.action='user.identity.normalize'
            AND a.object_type='user' AND a.object_id=u.id::text
            AND a.reason='保留内置模拟管理员登录名202630001并移除误填校园编号'
            AND a.before_values->>'campus_id'='202630001'
            AND a.after_values->>'campus_id' IS NULL
      )
)
INSERT INTO audit_events(actor_id,action,object_type,object_id,reason,before_values,after_values,occurred_at)
SELECT NULL,'user.identity.normalize','user',id::text,
       '恢复本项目确认的内置演示管理员9位校园编号',
       jsonb_build_object('username',username,'campus_id',campus_id),
       jsonb_build_object('username',username,'campus_id','202630001'),CURRENT_TIMESTAMP
FROM candidates;

WITH candidates AS (
    SELECT u.id
    FROM users u
    WHERE u.username='202630001' AND u.campus_id IS NULL
      AND u.real_name='管理员' AND u.role='admin'
      AND EXISTS (
          SELECT 1 FROM audit_events a
          WHERE a.action='user.identity.normalize'
            AND a.object_type='user' AND a.object_id=u.id::text
            AND a.before_values->>'username'='admin'
            AND a.after_values->>'username'='202630001'
      )
      AND EXISTS (
          SELECT 1 FROM audit_events a
          WHERE a.action='user.identity.normalize'
            AND a.object_type='user' AND a.object_id=u.id::text
            AND a.reason='保留内置模拟管理员登录名202630001并移除误填校园编号'
            AND a.before_values->>'campus_id'='202630001'
            AND a.after_values->>'campus_id' IS NULL
      )
)
UPDATE users u SET campus_id='202630001',updated_at=CURRENT_TIMESTAMP
FROM candidates c WHERE u.id=c.id;
