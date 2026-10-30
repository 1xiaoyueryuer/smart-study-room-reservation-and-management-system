-- Submission facts and their original owner/time FK remain unchanged.
ALTER TABLE appeals DROP CONSTRAINT appeals_status_check;
ALTER TABLE appeals ADD CONSTRAINT appeals_status_check CHECK(status IN('submitted','approved','rejected'));
ALTER TABLE appeals ADD CONSTRAINT appeal_id_owner_violation_unique UNIQUE(id,user_id,violation_id);
CREATE TABLE appeal_reviews (
 id BIGSERIAL PRIMARY KEY,
 appeal_id BIGINT NOT NULL UNIQUE,
 user_id BIGINT NOT NULL,
 violation_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL REFERENCES users(id),
 decision VARCHAR(20) NOT NULL CHECK(decision IN('approved','rejected')),
 reason VARCHAR(1000) NOT NULL CHECK(char_length(trim(reason)) BETWEEN 1 AND 1000),
 verification_note VARCHAR(2000) NOT NULL CHECK(char_length(trim(verification_note)) BETWEEN 1 AND 2000),
 facts_confirmed BOOLEAN NOT NULL CHECK(facts_confirmed),
 source VARCHAR(30) NOT NULL DEFAULT 'admin_self_report' CHECK(source='admin_self_report'),
 reviewed_at TIMESTAMP NOT NULL,
 appeal_version_before BIGINT NOT NULL CHECK(appeal_version_before>0),
 violation_status_before VARCHAR(20) NOT NULL CHECK(violation_status_before IN('active','revoked')),
 violation_action VARCHAR(30) NOT NULL CHECK(violation_action IN('revoked','already_revoked','unchanged')),
 operation_id UUID NOT NULL UNIQUE,
 request_hash VARCHAR(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 impact_hash VARCHAR(64) NOT NULL CHECK(impact_hash ~ '^[0-9a-f]{64}$'),
 booking_effects JSONB,
 FOREIGN KEY(appeal_id,user_id,violation_id) REFERENCES appeals(id,user_id,violation_id),
 UNIQUE(id,user_id,violation_id,decision),
 CHECK((decision='approved' AND booking_effects IS NOT NULL AND jsonb_typeof(booking_effects)='object' AND
        ((violation_status_before='active' AND violation_action='revoked') OR (violation_status_before='revoked' AND violation_action='already_revoked'))) OR
       (decision='rejected' AND violation_action='unchanged' AND booking_effects IS NULL))
);
CREATE UNIQUE INDEX idx_violation_actual_appeal_revocation ON appeal_reviews(violation_id) WHERE violation_action='revoked';
ALTER TABLE restriction_evaluations ADD CONSTRAINT evaluation_original_window_unique UNIQUE(restriction_id,user_id,evaluated_at,window_start,window_end);
ALTER TABLE restriction_evaluation_sources ADD CONSTRAINT consumed_batch_violation_owner_unique UNIQUE(restriction_id,violation_id,user_id);
CREATE TABLE restriction_recalculations (
 id BIGSERIAL PRIMARY KEY,
 review_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 violation_id BIGINT NOT NULL,
 decision VARCHAR(20) NOT NULL DEFAULT 'approved' CHECK(decision='approved'),
 restriction_id BIGINT NOT NULL,
 status_before VARCHAR(20) NOT NULL CHECK(status_before IN('active','expired','released')),
 status_after VARCHAR(20) NOT NULL CHECK(status_after IN('active','expired','released')),
 outcome VARCHAR(30) NOT NULL CHECK(outcome IN('released','retained','expired_recorded','released_recorded')),
 valid_count_before INTEGER NOT NULL CHECK(valid_count_before BETWEEN 0 AND 3),
 valid_count_after INTEGER NOT NULL CHECK(valid_count_after BETWEEN 0 AND 3 AND valid_count_after<=valid_count_before),
 threshold INTEGER NOT NULL DEFAULT 3 CHECK(threshold=3),
 evaluated_at TIMESTAMP NOT NULL,
 window_start TIMESTAMP NOT NULL,
 window_end TIMESTAMP NOT NULL,
 starts_at TIMESTAMP NOT NULL,
 ends_at TIMESTAMP NOT NULL,
 source_violations JSONB NOT NULL CHECK(jsonb_typeof(source_violations)='array' AND jsonb_array_length(source_violations)=3),
 UNIQUE(review_id,restriction_id),
 FOREIGN KEY(review_id,user_id,violation_id,decision) REFERENCES appeal_reviews(id,user_id,violation_id,decision) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(restriction_id,violation_id,user_id) REFERENCES restriction_evaluation_sources(restriction_id,violation_id,user_id),
 FOREIGN KEY(restriction_id,user_id,evaluated_at,window_start,window_end) REFERENCES restriction_evaluations(restriction_id,user_id,evaluated_at,window_start,window_end),
 CHECK(starts_at=evaluated_at AND ends_at=starts_at+INTERVAL '168 hours' AND window_end=evaluated_at AND window_start=evaluated_at-INTERVAL '720 hours'),
 CHECK((outcome='released' AND status_before='active' AND status_after='released' AND valid_count_after<3) OR
       (outcome='retained' AND status_before='active' AND status_after='active' AND valid_count_after>=3) OR
       (outcome='expired_recorded' AND status_before='expired' AND status_after='expired') OR
       (outcome='released_recorded' AND status_before='released' AND status_after='released'))
);
-- Reserve a review ID in confirm, perform existing domain writes, then INSERT the complete immutable actual review once.
CREATE FUNCTION reject_appeal_review_fact_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'appeal review and recalculation facts are immutable' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_appeal_review BEFORE UPDATE ON appeal_reviews FOR EACH ROW EXECUTE FUNCTION reject_appeal_review_fact_update();
CREATE TRIGGER immutable_restriction_recalculation BEFORE UPDATE ON restriction_recalculations FOR EACH ROW EXECUTE FUNCTION reject_appeal_review_fact_update();
-- Deferred so all outcomes are recorded atomically; terminal appeal states cannot exist without their unique actual review.
CREATE FUNCTION validate_appeal_review_outcome() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE target BIGINT; appeal appeals%ROWTYPE; review appeal_reviews%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='appeals' THEN target=CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END;
 ELSE target=CASE WHEN TG_OP='DELETE' THEN OLD.appeal_id ELSE NEW.appeal_id END; END IF;
 SELECT * INTO appeal FROM appeals WHERE id=target;
 IF NOT FOUND THEN RETURN NULL; END IF;
 SELECT * INTO review FROM appeal_reviews WHERE appeal_id=target;
 IF appeal.status='submitted' THEN
  IF FOUND THEN RAISE EXCEPTION 'submitted appeal cannot already own a review' USING ERRCODE='23514'; END IF;
 ELSIF NOT FOUND OR appeal.status<>review.decision OR appeal.version<>review.appeal_version_before+1 OR review.reviewed_at<appeal.submitted_at THEN
  RAISE EXCEPTION 'terminal appeal requires its actual review and next version' USING ERRCODE='23514';
 ELSIF review.decision='approved' AND NOT EXISTS(SELECT 1 FROM violations WHERE id=review.violation_id AND user_id=review.user_id AND status='revoked') THEN
  RAISE EXCEPTION 'approved appeal requires the original violation to remain revoked' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER appeal_review_outcome_check AFTER INSERT OR UPDATE OR DELETE ON appeals DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_appeal_review_outcome();
CREATE CONSTRAINT TRIGGER reviewed_appeal_outcome_check AFTER INSERT OR DELETE ON appeal_reviews DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_appeal_review_outcome();
CREATE FUNCTION validate_appeal_review_recalculation_count() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE target BIGINT; review appeal_reviews%ROWTYPE; expected_count BIGINT; actual_count BIGINT;
BEGIN
 IF TG_TABLE_NAME='appeal_reviews' THEN target=CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END;
 ELSE target=CASE WHEN TG_OP='DELETE' THEN OLD.review_id ELSE NEW.review_id END; END IF;
 SELECT * INTO review FROM appeal_reviews WHERE id=target;
 IF NOT FOUND THEN RETURN NULL; END IF;
 expected_count=0;
 IF review.decision='approved' THEN
  SELECT count(*) INTO expected_count FROM restriction_evaluation_sources WHERE violation_id=review.violation_id AND user_id=review.user_id;
 END IF;
 SELECT count(*) INTO actual_count FROM restriction_recalculations WHERE review_id=target;
 IF expected_count<>actual_count THEN RAISE EXCEPTION 'appeal review requires exactly its original consumed batch recalculation' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER reviewed_recalculation_count_check AFTER INSERT OR DELETE ON appeal_reviews DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_appeal_review_recalculation_count();
CREATE CONSTRAINT TRIGGER original_batch_recalculation_count_check AFTER INSERT OR DELETE ON restriction_recalculations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_appeal_review_recalculation_count();
ALTER TABLE notifications ADD COLUMN appeal_id BIGINT UNIQUE;
ALTER TABLE notifications ADD CONSTRAINT notification_appeal_owner_fk FOREIGN KEY(appeal_id,user_id) REFERENCES appeals(id,user_id);
ALTER TABLE notifications ADD CONSTRAINT notification_appeal_type_check CHECK(
 appeal_id IS NULL OR (type='appeal_reviewed' AND complaint_id IS NULL AND violation_id IS NULL AND restriction_id IS NULL));
