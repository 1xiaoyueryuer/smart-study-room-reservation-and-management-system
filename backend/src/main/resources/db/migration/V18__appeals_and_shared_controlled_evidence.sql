-- Appeal deadlines derive exclusively from a real typed violation notification.
ALTER TABLE notifications ADD CONSTRAINT notification_id_owner_violation_time_unique UNIQUE(id,user_id,violation_id,created_at);
CREATE TABLE appeals (
 id BIGSERIAL PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES users(id),
 violation_id BIGINT NOT NULL,
 operation_id UUID NOT NULL UNIQUE,
 request_hash VARCHAR(64) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'submitted' CHECK(status='submitted'),
 version BIGINT NOT NULL DEFAULT 1 CHECK(version>0),
 description VARCHAR(2000) NOT NULL CHECK(char_length(trim(description)) BETWEEN 1 AND 2000),
 submitted_at TIMESTAMP NOT NULL,
 notification_id BIGINT NOT NULL,
 notified_at TIMESTAMP NOT NULL,
 deadline TIMESTAMP NOT NULL,
 violation_snapshot JSONB NOT NULL CHECK(jsonb_typeof(violation_snapshot)='object'),
 FOREIGN KEY(violation_id,user_id) REFERENCES violations(id,user_id),
 FOREIGN KEY(notification_id,user_id,violation_id,notified_at) REFERENCES notifications(id,user_id,violation_id,created_at),
 UNIQUE(id,user_id),
 UNIQUE(id,user_id,submitted_at),
 CHECK(deadline=notified_at+INTERVAL '168 hours' AND submitted_at>=notified_at AND submitted_at<deadline)
);
CREATE UNIQUE INDEX idx_appeal_pending_violation ON appeals(violation_id) WHERE status='submitted';
CREATE INDEX idx_appeals_owner_time ON appeals(user_id,submitted_at DESC,id DESC);
CREATE TABLE appeal_events (
 id BIGSERIAL PRIMARY KEY,
 appeal_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 event_type VARCHAR(30) NOT NULL CHECK(event_type='submitted'),
 source VARCHAR(30) NOT NULL CHECK(source='user_submit'),
 occurred_at TIMESTAMP NOT NULL,
 FOREIGN KEY(appeal_id,actor_id,occurred_at) REFERENCES appeals(id,user_id,submitted_at)
);
CREATE UNIQUE INDEX idx_appeal_submitted_once ON appeal_events(appeal_id) WHERE event_type='submitted';
ALTER TABLE complaints ADD CONSTRAINT complaint_id_owner_unique UNIQUE(id,user_id);
ALTER TABLE complaint_attachments RENAME TO evidence_attachments;
ALTER TABLE evidence_attachments ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'complaint' CHECK(purpose IN('complaint','appeal'));
ALTER TABLE evidence_attachments ADD COLUMN appeal_id BIGINT;
ALTER TABLE evidence_attachments ADD CONSTRAINT evidence_complaint_owner_fk FOREIGN KEY(complaint_id,owner_id) REFERENCES complaints(id,user_id);
ALTER TABLE evidence_attachments ADD CONSTRAINT evidence_appeal_owner_fk FOREIGN KEY(appeal_id,owner_id) REFERENCES appeals(id,user_id);
ALTER TABLE evidence_attachments DROP CONSTRAINT complaint_attachments_check3;
ALTER TABLE evidence_attachments ADD CONSTRAINT evidence_binding_check CHECK(
 (complaint_id IS NULL AND appeal_id IS NULL AND position IS NULL AND bound_at IS NULL) OR
 (position IS NOT NULL AND position BETWEEN 0 AND 2 AND bound_at IS NOT NULL AND
  ((purpose='complaint' AND complaint_id IS NOT NULL AND appeal_id IS NULL) OR
   (purpose='appeal' AND appeal_id IS NOT NULL AND complaint_id IS NULL))));
CREATE UNIQUE INDEX idx_appeal_evidence_position ON evidence_attachments(appeal_id,position) WHERE appeal_id IS NOT NULL;
DROP INDEX idx_complaint_material_expiry;
CREATE INDEX idx_evidence_material_expiry ON evidence_attachments(expires_at) WHERE complaint_id IS NULL AND appeal_id IS NULL;
