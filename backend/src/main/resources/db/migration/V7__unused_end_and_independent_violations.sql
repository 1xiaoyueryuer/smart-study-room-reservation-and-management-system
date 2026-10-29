ALTER TABLE reservations DROP CONSTRAINT reservations_status_check;
ALTER TABLE reservations ADD COLUMN ended_at TIMESTAMP;
ALTER TABLE reservations ADD COLUMN termination_reason VARCHAR(40);
ALTER TABLE reservations ADD COLUMN legacy_status VARCHAR(20);
-- 真实旧标记只作溯源，不从信用或模拟历史推造独立违规。
UPDATE reservations SET legacy_status=status,status='unused_ended',termination_reason='legacy_violation' WHERE status='violation';
ALTER TABLE reservations ADD CONSTRAINT reservations_status_check CHECK(status IN ('pending','checked_in','temp_leave','completed','cancelled','unused_ended'));
CREATE TABLE violations (
 id BIGSERIAL PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES users(id),
 reservation_id BIGINT NOT NULL REFERENCES reservations(id),
 type VARCHAR(30) NOT NULL CHECK(type IN ('abandon','no_show')),
 event_key VARCHAR(80) NOT NULL UNIQUE,
 occurred_at TIMESTAMP NOT NULL,
 registered_at TIMESTAMP NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK(status IN ('active','revoked')),
 reason VARCHAR(1000) NOT NULL,
 source VARCHAR(30) NOT NULL DEFAULT 'automatic'
);
CREATE INDEX idx_violations_user_time ON violations(user_id,occurred_at DESC,id DESC);
