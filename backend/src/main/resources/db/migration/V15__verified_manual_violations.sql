-- Typed notifications prove their real generation time; no old title/text timestamps are inferred.
ALTER TABLE violations DROP CONSTRAINT violations_type_check;
ALTER TABLE violations ADD CONSTRAINT violations_type_check CHECK(type IN('abandon','no_show','occupation_refusal'));
ALTER TABLE violations ADD COLUMN registrant_id BIGINT REFERENCES users(id);
ALTER TABLE violations ADD COLUMN source_event_id BIGINT UNIQUE REFERENCES complaint_events(id);
ALTER TABLE violations ADD COLUMN operation_id UUID UNIQUE;
ALTER TABLE violations ADD COLUMN request_hash VARCHAR(64);
ALTER TABLE violations ADD CONSTRAINT violations_source_check CHECK(source IN('automatic','manual'));
ALTER TABLE violations ADD CONSTRAINT violations_origin_check CHECK(
 (source='automatic' AND type IN('abandon','no_show') AND registrant_id IS NULL AND source_event_id IS NULL AND operation_id IS NULL AND request_hash IS NULL) OR
 (source='manual' AND type='occupation_refusal' AND registrant_id IS NOT NULL AND source_event_id IS NOT NULL AND operation_id IS NOT NULL AND request_hash IS NOT NULL AND char_length(trim(reason)) BETWEEN 1 AND 1000));
-- Revoked records also keep their identity: a new key cannot silently re-register the same fact.
CREATE UNIQUE INDEX idx_manual_violation_fact ON violations(reservation_id,user_id,type,occurred_at) WHERE source='manual';
ALTER TABLE violations ADD CONSTRAINT violations_id_user_unique UNIQUE(id,user_id);
ALTER TABLE notifications ADD COLUMN violation_id BIGINT UNIQUE;
ALTER TABLE notifications ADD CONSTRAINT notification_violation_owner_fk FOREIGN KEY(violation_id,user_id) REFERENCES violations(id,user_id);
ALTER TABLE notifications ADD CONSTRAINT notification_violation_type_check CHECK(violation_id IS NULL OR (type='violation' AND complaint_id IS NULL));
