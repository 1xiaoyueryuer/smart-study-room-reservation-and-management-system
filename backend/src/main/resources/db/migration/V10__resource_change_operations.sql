ALTER TABLE reservations ADD COLUMN termination_note VARCHAR(1000);
CREATE TABLE resource_change_operations (
 operation_id UUID PRIMARY KEY,
 actor_id BIGINT NOT NULL REFERENCES users(id),
 request_hash VARCHAR(64) NOT NULL,
 impact_hash VARCHAR(64) NOT NULL,
 proposal JSONB NOT NULL,
 coordination JSONB NOT NULL,
 result JSONB NOT NULL,
 created_at TIMESTAMP NOT NULL
);
