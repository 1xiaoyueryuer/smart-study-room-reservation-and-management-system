ALTER TABLE usage_records DROP CONSTRAINT usage_records_origin_check;
ALTER TABLE usage_records ADD CONSTRAINT usage_records_origin_check CHECK(origin IN('legacy','seat_code','demo_seed','admin_correction'));
ALTER TABLE usage_records ADD COLUMN correction_operation UUID UNIQUE;
ALTER TABLE usage_records ADD COLUMN correction_actor BIGINT REFERENCES users(id);
ALTER TABLE usage_records ADD COLUMN corrected_at TIMESTAMP;
ALTER TABLE usage_records ADD COLUMN correction_reason VARCHAR(1000);
ALTER TABLE usage_records ADD COLUMN correction_note VARCHAR(2000);
ALTER TABLE usage_records ADD COLUMN correction_request JSONB;
ALTER TABLE usage_records ADD CONSTRAINT correction_fact_complete CHECK(
 (origin='admin_correction' AND correction_operation IS NOT NULL AND correction_actor IS NOT NULL AND corrected_at IS NOT NULL
 AND corrected_at>=checkin_at AND created_at=corrected_at AND correction_reason IS NOT NULL AND char_length(trim(correction_reason)) BETWEEN 1 AND 1000
 AND correction_note IS NOT NULL AND char_length(trim(correction_note)) BETWEEN 1 AND 2000
 AND correction_request IS NOT NULL AND jsonb_typeof(correction_request)='object') OR
 (origin<>'admin_correction' AND correction_operation IS NULL AND correction_actor IS NULL AND corrected_at IS NULL AND correction_reason IS NULL AND correction_note IS NULL AND correction_request IS NULL));
CREATE FUNCTION preserve_checkin_correction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.reservation_id,NEW.checkin_at,NEW.origin,NEW.created_at,NEW.correction_operation,NEW.correction_actor,NEW.corrected_at,NEW.correction_reason,NEW.correction_note,NEW.correction_request)
 IS DISTINCT FROM ROW(OLD.reservation_id,OLD.checkin_at,OLD.origin,OLD.created_at,OLD.correction_operation,OLD.correction_actor,OLD.corrected_at,OLD.correction_reason,OLD.correction_note,OLD.correction_request)
 THEN RAISE EXCEPTION 'checkin facts are immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER preserve_checkin_correction BEFORE UPDATE ON usage_records FOR EACH ROW EXECUTE FUNCTION preserve_checkin_correction();
