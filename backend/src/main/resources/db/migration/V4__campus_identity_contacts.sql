ALTER TABLE users ADD COLUMN campus_id VARCHAR(20) UNIQUE;
ALTER TABLE users ADD COLUMN phone VARCHAR(30);
ALTER TABLE users ADD COLUMN email VARCHAR(254);
-- 原值保留，未知身份仍为NULL；旧列只作溯源，不再作为新写源。
UPDATE users SET campus_id=CASE WHEN btrim(student_no)='' THEN NULL ELSE student_no END WHERE student_no IS NOT NULL;
