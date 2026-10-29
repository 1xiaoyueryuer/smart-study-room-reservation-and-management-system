ALTER TABLE complaints DROP CONSTRAINT complaints_status_check;
ALTER TABLE complaints ADD COLUMN version BIGINT NOT NULL DEFAULT 1 CHECK(version>0);
ALTER TABLE complaints ADD COLUMN handler_id BIGINT REFERENCES users(id);
ALTER TABLE complaints ADD COLUMN accepted_at TIMESTAMP;
ALTER TABLE complaints ADD CONSTRAINT complaints_status_check CHECK(status IN('submitted','processing'));
ALTER TABLE complaints ADD CONSTRAINT complaints_handler_check CHECK((status='submitted' AND handler_id IS NULL AND accepted_at IS NULL) OR (status='processing' AND handler_id IS NOT NULL AND accepted_at IS NOT NULL));
ALTER TABLE complaint_events DROP CONSTRAINT complaint_events_event_type_check;
ALTER TABLE complaint_events DROP CONSTRAINT complaint_events_source_check;
ALTER TABLE complaint_events ADD COLUMN operation_id UUID UNIQUE;
ALTER TABLE complaint_events ADD COLUMN request_hash VARCHAR(64);
ALTER TABLE complaint_events ADD COLUMN occurred_at TIMESTAMP;
ALTER TABLE complaint_events ADD COLUMN outcome VARCHAR(30);
ALTER TABLE complaint_events ADD COLUMN public_note VARCHAR(1000);
ALTER TABLE complaint_events ADD COLUMN private_note VARCHAR(2000);
ALTER TABLE complaint_events ADD COLUMN subject_user_id BIGINT REFERENCES users(id);
ALTER TABLE complaint_events ADD COLUMN identity_confirmed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE complaint_events ADD COLUMN identity_basis VARCHAR(1000);
ALTER TABLE complaint_events ADD COLUMN facts_confirmed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE complaint_events ADD COLUMN valid_reservation_confirmed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_event_type_check CHECK(event_type IN('submitted','accepted','verification'));
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_source_check CHECK(source IN('user_submit','admin_self_report'));
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_operation_check CHECK((event_type='submitted' AND operation_id IS NULL AND request_hash IS NULL) OR (event_type<>'submitted' AND operation_id IS NOT NULL AND request_hash IS NOT NULL));
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_verification_check CHECK((event_type<>'verification' AND outcome IS NULL) OR (event_type='verification' AND outcome IS NOT NULL AND outcome IN('cooperated','identity_pending','unsubstantiated','verified_refusal','service_verified') AND occurred_at IS NOT NULL AND public_note IS NOT NULL AND private_note IS NOT NULL));
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_identity_check CHECK((subject_user_id IS NULL AND NOT identity_confirmed AND identity_basis IS NULL) OR (subject_user_id IS NOT NULL AND identity_confirmed AND identity_basis IS NOT NULL AND char_length(identity_basis)>0));
CREATE UNIQUE INDEX idx_complaint_accepted_once ON complaint_events(complaint_id) WHERE event_type='accepted';
CREATE TABLE reminders (
 id BIGSERIAL PRIMARY KEY,
 event_id BIGINT NOT NULL UNIQUE REFERENCES complaint_events(id),
 subject_user_id BIGINT REFERENCES users(id),
 actor_id BIGINT NOT NULL REFERENCES users(id),
 occurred_at TIMESTAMP NOT NULL,
 registered_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_reminders_subject_time ON reminders(subject_user_id,registered_at DESC,id DESC);
