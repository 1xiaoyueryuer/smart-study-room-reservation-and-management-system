-- Old messages keep their original text, recipient, read flag and creation time.
-- Missing legacy associations/event identity remain NULL; never infer targets from text.
ALTER TABLE notifications ADD COLUMN reservation_id BIGINT;
ALTER TABLE notifications ADD COLUMN complaint_event_id BIGINT UNIQUE;
ALTER TABLE notifications ADD COLUMN event_key VARCHAR(200) CHECK(event_key IS NULL OR char_length(trim(event_key))>0);
ALTER TABLE notifications ADD CONSTRAINT notification_owner_event_key_unique UNIQUE(user_id,event_key);
ALTER TABLE notifications ADD CONSTRAINT notification_reservation_owner_fk FOREIGN KEY(reservation_id,user_id) REFERENCES reservations(id,user_id);
ALTER TABLE notifications ADD CONSTRAINT notification_complaint_owner_fk FOREIGN KEY(complaint_id,user_id) REFERENCES complaints(id,user_id);
ALTER TABLE complaint_events ADD CONSTRAINT complaint_event_id_complaint_type_unique UNIQUE(id,complaint_id,event_type);
ALTER TABLE notifications ADD COLUMN complaint_event_type VARCHAR(30) GENERATED ALWAYS AS (
 CASE WHEN complaint_event_id IS NULL THEN NULL WHEN type='complaint_feedback' THEN 'feedback' WHEN type='complaint_auto_closed' THEN 'auto_closed' END) STORED;
ALTER TABLE notifications ADD CONSTRAINT notification_actual_complaint_event_fk FOREIGN KEY(complaint_event_id,complaint_id,complaint_event_type) REFERENCES complaint_events(id,complaint_id,event_type);
ALTER TABLE notifications ADD CONSTRAINT notification_complaint_event_complete CHECK(complaint_event_id IS NULL OR (complaint_id IS NOT NULL AND type IN('complaint_feedback','complaint_auto_closed')));
ALTER TABLE notifications ADD CONSTRAINT notification_reservation_type_check CHECK(reservation_id IS NULL OR (type IN('reservation_success','reservation_cancelled','reservation_checkin','reservation_completed','management_cancelled','restriction_cancelled','reservation_corrected_checkin') AND violation_id IS NULL AND complaint_id IS NULL AND appeal_id IS NULL AND review_reservation_id IS NULL AND (type='restriction_cancelled')=(restriction_id IS NOT NULL)));
CREATE UNIQUE INDEX notification_reservation_actual_action_unique ON notifications(reservation_id,type) WHERE reservation_id IS NOT NULL;
ALTER TABLE notifications ADD CONSTRAINT notification_new_event_typed CHECK(event_key IS NULL OR
 (type IN('reservation_success','reservation_cancelled','reservation_checkin','reservation_completed','management_cancelled','restriction_cancelled','reservation_corrected_checkin') AND reservation_id IS NOT NULL) OR
 (type='violation' AND violation_id IS NOT NULL) OR
 (type IN('restriction_started','restriction_released') AND restriction_id IS NOT NULL) OR
 (type IN('complaint_feedback','complaint_auto_closed') AND complaint_id IS NOT NULL AND complaint_event_id IS NOT NULL) OR
 (type='appeal_reviewed' AND appeal_id IS NOT NULL) OR
 (type IN('review_replied','review_hidden') AND review_reservation_id IS NOT NULL AND review_operation_id IS NOT NULL));
