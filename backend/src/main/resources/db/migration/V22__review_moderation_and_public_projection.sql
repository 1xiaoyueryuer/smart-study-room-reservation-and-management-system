ALTER TABLE reviews ADD COLUMN admin_reply VARCHAR(2000);
ALTER TABLE reviews ADD COLUMN replied_at TIMESTAMP;
ALTER TABLE reviews ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE reviews ADD COLUMN hidden_reason VARCHAR(1000);
ALTER TABLE reviews ADD COLUMN hidden_at TIMESTAMP;
ALTER TABLE reviews ADD CONSTRAINT review_reply_complete CHECK((admin_reply IS NULL AND replied_at IS NULL) OR (admin_reply IS NOT NULL AND replied_at IS NOT NULL AND char_length(trim(admin_reply)) BETWEEN 1 AND 2000));
ALTER TABLE reviews ADD CONSTRAINT review_hidden_complete CHECK((NOT hidden AND hidden_reason IS NULL AND hidden_at IS NULL) OR (hidden AND hidden_reason IS NOT NULL AND hidden_at IS NOT NULL AND char_length(trim(hidden_reason)) BETWEEN 1 AND 1000));
ALTER TABLE reviews ADD CONSTRAINT review_id_reservation_unique UNIQUE(id,reservation_id);
CREATE FUNCTION preserve_review_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.user_id,NEW.reservation_id,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.reservation_id,OLD.created_at)
 THEN RAISE EXCEPTION 'review identity is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER preserve_review_identity BEFORE UPDATE ON reviews FOR EACH ROW EXECUTE FUNCTION preserve_review_identity();
-- Existing owner operations were validated by this FK. They remain immutable; new inserts
-- keep the same conditional owner rule while permitting independent administrator actors.
ALTER TABLE review_operations DROP CONSTRAINT review_operations_review_id_actor_id_reservation_id_fkey;
ALTER TABLE review_operations ADD CONSTRAINT review_operation_review_reservation_fk FOREIGN KEY(review_id,reservation_id) REFERENCES reviews(id,reservation_id);
ALTER TABLE review_operations ADD CONSTRAINT review_operation_actor_fk FOREIGN KEY(actor_id) REFERENCES users(id);
ALTER TABLE review_operations DROP CONSTRAINT review_operations_action_check;
ALTER TABLE review_operations ADD CONSTRAINT review_operations_action_check CHECK(action IN('create','update','reply','hide'));
ALTER TABLE review_operations ADD CONSTRAINT review_operation_id_reservation_unique UNIQUE(operation_id,reservation_id);
CREATE FUNCTION validate_review_operation_actor() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.action IN('create','update') THEN
  IF NOT EXISTS(SELECT 1 FROM reviews WHERE id=NEW.review_id AND reservation_id=NEW.reservation_id AND user_id=NEW.actor_id)
  THEN RAISE EXCEPTION 'owner operation requires actual review owner' USING ERRCODE='23514'; END IF;
 ELSIF NOT EXISTS(SELECT 1 FROM users WHERE id=NEW.actor_id AND role='admin' AND status='active') THEN
  RAISE EXCEPTION 'management operation requires active administrator' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER review_operation_actor_check BEFORE INSERT ON review_operations FOR EACH ROW EXECUTE FUNCTION validate_review_operation_actor();
ALTER TABLE notifications ADD COLUMN review_reservation_id BIGINT;
ALTER TABLE notifications ADD COLUMN review_operation_id UUID UNIQUE;
ALTER TABLE notifications ADD CONSTRAINT review_notification_owner_fk FOREIGN KEY(review_reservation_id,user_id) REFERENCES reservations(id,user_id);
ALTER TABLE notifications ADD CONSTRAINT review_notification_operation_fk FOREIGN KEY(review_operation_id,review_reservation_id) REFERENCES review_operations(operation_id,reservation_id);
ALTER TABLE notifications ADD CONSTRAINT review_notification_complete CHECK(
 (review_reservation_id IS NULL AND review_operation_id IS NULL) OR
 (review_reservation_id IS NOT NULL AND review_operation_id IS NOT NULL AND type IN('review_replied','review_hidden') AND complaint_id IS NULL AND violation_id IS NULL AND restriction_id IS NULL AND appeal_id IS NULL));
CREATE FUNCTION validate_review_notification_action() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.review_operation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM review_operations WHERE operation_id=NEW.review_operation_id AND action=CASE NEW.type WHEN 'review_replied' THEN 'reply' WHEN 'review_hidden' THEN 'hide' ELSE '' END)
 THEN RAISE EXCEPTION 'review notification must match actual management action' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER review_notification_action_check BEFORE INSERT OR UPDATE ON notifications FOR EACH ROW EXECUTE FUNCTION validate_review_notification_action();
