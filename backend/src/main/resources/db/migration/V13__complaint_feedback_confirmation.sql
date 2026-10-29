-- Immutable feedback events own the exact seven-day deadline; lifecycle responses reference their original round.
ALTER TABLE complaints ALTER COLUMN status TYPE VARCHAR(30);
ALTER TABLE complaints DROP CONSTRAINT complaints_status_check;
ALTER TABLE complaints DROP CONSTRAINT complaints_handler_check;
ALTER TABLE complaints ADD CONSTRAINT complaints_status_check CHECK(status IN('submitted','processing','awaiting_confirmation','closed','withdrawn'));
ALTER TABLE complaints ADD CONSTRAINT complaints_handler_check CHECK((status IN('submitted','withdrawn') AND handler_id IS NULL AND accepted_at IS NULL) OR (status IN('processing','awaiting_confirmation','closed') AND handler_id IS NOT NULL AND accepted_at IS NOT NULL));
ALTER TABLE complaint_events ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE complaint_events DROP CONSTRAINT complaint_events_event_type_check;
ALTER TABLE complaint_events DROP CONSTRAINT complaint_events_source_check;
ALTER TABLE complaint_events DROP CONSTRAINT complaint_events_operation_check;
ALTER TABLE complaint_events ADD COLUMN feedback_result VARCHAR(2000);
ALTER TABLE complaint_events ADD COLUMN feedback_basis VARCHAR(2000);
ALTER TABLE complaint_events ADD COLUMN feedback_deadline TIMESTAMP;
ALTER TABLE complaint_events ADD COLUMN related_feedback_id BIGINT;
ALTER TABLE complaint_events ADD COLUMN response_note VARCHAR(2000);
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_id_complaint_unique UNIQUE(id,complaint_id);
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_related_feedback_fk FOREIGN KEY(related_feedback_id,complaint_id) REFERENCES complaint_events(id,complaint_id);
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_event_type_check CHECK(event_type IN('submitted','accepted','verification','feedback','withdrawn','confirmed','objected','auto_closed'));
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_source_check CHECK(source IN('user_submit','admin_self_report','system'));
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_operation_check CHECK(
 (event_type='submitted' AND source='user_submit' AND actor_id IS NOT NULL AND operation_id IS NULL AND request_hash IS NULL) OR
 (event_type IN('withdrawn','confirmed','objected') AND source='user_submit' AND actor_id IS NOT NULL AND operation_id IS NOT NULL AND request_hash IS NOT NULL) OR
 (event_type IN('accepted','verification','feedback') AND source='admin_self_report' AND actor_id IS NOT NULL AND operation_id IS NOT NULL AND request_hash IS NOT NULL) OR
 (event_type='auto_closed' AND source='system' AND actor_id IS NULL AND operation_id IS NULL AND request_hash IS NULL));
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_feedback_check CHECK(
 (event_type='feedback' AND feedback_result IS NOT NULL AND char_length(trim(feedback_result)) BETWEEN 1 AND 2000 AND feedback_basis IS NOT NULL AND char_length(trim(feedback_basis)) BETWEEN 1 AND 2000 AND feedback_deadline IS NOT NULL AND feedback_deadline=created_at+INTERVAL '7 days') OR
 (event_type<>'feedback' AND feedback_result IS NULL AND feedback_basis IS NULL AND feedback_deadline IS NULL));
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_response_check CHECK(
 (event_type IN('confirmed','objected','auto_closed') AND related_feedback_id IS NOT NULL AND ((event_type='objected' AND response_note IS NOT NULL AND char_length(trim(response_note)) BETWEEN 1 AND 2000) OR (event_type<>'objected' AND response_note IS NULL))) OR
 (event_type NOT IN('confirmed','objected','auto_closed') AND related_feedback_id IS NULL AND response_note IS NULL));
CREATE UNIQUE INDEX idx_complaint_feedback_response_once ON complaint_events(related_feedback_id) WHERE event_type IN('confirmed','objected','auto_closed');
CREATE INDEX idx_complaint_feedback_deadline ON complaint_events(feedback_deadline,complaint_id) WHERE event_type='feedback';
ALTER TABLE notifications ADD COLUMN complaint_id BIGINT REFERENCES complaints(id);
