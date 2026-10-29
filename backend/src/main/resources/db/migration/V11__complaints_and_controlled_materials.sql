CREATE TABLE complaints (
 id BIGSERIAL PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES users(id),
 operation_id UUID NOT NULL UNIQUE,
 request_hash VARCHAR(64) NOT NULL,
 type VARCHAR(20) NOT NULL CHECK(type IN('occupation','service')),
 status VARCHAR(20) NOT NULL DEFAULT 'submitted' CHECK(status='submitted'),
 description VARCHAR(2000) NOT NULL CHECK(char_length(description) BETWEEN 1 AND 2000),
 reservation_id BIGINT REFERENCES reservations(id),
 room_id BIGINT REFERENCES rooms(id),
 context_snapshot JSONB,
 submitted_at TIMESTAMP NOT NULL,
 CHECK(type<>'occupation' OR reservation_id IS NOT NULL)
);
CREATE INDEX idx_complaints_owner_time ON complaints(user_id,submitted_at DESC,id DESC);
CREATE TABLE complaint_events (
 id BIGSERIAL PRIMARY KEY,
 complaint_id BIGINT NOT NULL REFERENCES complaints(id),
 actor_id BIGINT NOT NULL REFERENCES users(id),
 event_type VARCHAR(30) NOT NULL CHECK(event_type='submitted'),
 source VARCHAR(30) NOT NULL CHECK(source='user_submit'),
 created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX idx_complaints_submitted_once ON complaint_events(complaint_id) WHERE event_type='submitted';
CREATE TABLE complaint_attachments (
 id UUID PRIMARY KEY,
 owner_id BIGINT NOT NULL REFERENCES users(id),
 complaint_id BIGINT REFERENCES complaints(id),
 position INTEGER,
 bound_at TIMESTAMP,
 display_name VARCHAR(100) NOT NULL,
 content_type VARCHAR(30) NOT NULL CHECK(content_type IN('image/png','image/jpeg')),
 size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 5242880),
 width INTEGER NOT NULL CHECK(width BETWEEN 1 AND 10000),
 height INTEGER NOT NULL CHECK(height BETWEEN 1 AND 10000),
 sha256 VARCHAR(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 file_bytes BYTEA NOT NULL,
 created_at TIMESTAMP NOT NULL,
 expires_at TIMESTAMP NOT NULL,
 CHECK(width::bigint*height<=20000000),
 CHECK(octet_length(file_bytes)=size_bytes),
 CHECK(expires_at>created_at),
 CHECK((complaint_id IS NULL AND position IS NULL AND bound_at IS NULL) OR (complaint_id IS NOT NULL AND position BETWEEN 0 AND 2 AND bound_at IS NOT NULL)),
 UNIQUE(complaint_id,position)
);
CREATE INDEX idx_complaint_material_expiry ON complaint_attachments(expires_at) WHERE complaint_id IS NULL;
