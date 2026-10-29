CREATE TABLE audit_events (
 id BIGSERIAL PRIMARY KEY,
 actor_id BIGINT REFERENCES users(id),
 action VARCHAR(80) NOT NULL CHECK (length(trim(action)) > 0),
 object_type VARCHAR(80) NOT NULL CHECK (length(trim(object_type)) > 0),
 object_id VARCHAR(100) NOT NULL CHECK (length(trim(object_id)) > 0),
 reason VARCHAR(1000) NOT NULL CHECK (length(trim(reason)) > 0),
 before_values JSONB NOT NULL,
 after_values JSONB NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_audit_object_time ON audit_events(object_type, object_id, occurred_at);
CREATE INDEX idx_audit_actor_time ON audit_events(actor_id, occurred_at);
