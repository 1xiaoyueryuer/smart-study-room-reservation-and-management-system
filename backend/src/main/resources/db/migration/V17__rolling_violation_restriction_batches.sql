-- Only new automatic batches receive a durable evaluation and exactly three permanently consumed sources.
ALTER TABLE restrictions DROP CONSTRAINT restrictions_source_check;
ALTER TABLE restrictions ADD CONSTRAINT restrictions_source_check CHECK(source IN('manual','legacy','automatic'));
ALTER TABLE restrictions DROP CONSTRAINT restrictions_check2;
ALTER TABLE restrictions ADD CONSTRAINT restriction_origin_check CHECK(
 (source='manual' AND creator_id IS NOT NULL AND facts_confirmed AND verification_note IS NOT NULL AND char_length(trim(verification_note)) BETWEEN 1 AND 2000) OR
 (source IN('legacy','automatic') AND creator_id IS NULL AND NOT facts_confirmed AND verification_note IS NULL));
ALTER TABLE restrictions ADD CONSTRAINT restriction_id_user_source_unique UNIQUE(id,user_id,source);
CREATE TABLE restriction_evaluations (
 restriction_id BIGINT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 source VARCHAR(20) NOT NULL DEFAULT 'automatic' CHECK(source='automatic'),
 evaluated_at TIMESTAMP NOT NULL,
 window_start TIMESTAMP NOT NULL,
 window_end TIMESTAMP NOT NULL,
 window_start_inclusive BOOLEAN NOT NULL DEFAULT true CHECK(window_start_inclusive),
 window_end_inclusive BOOLEAN NOT NULL DEFAULT true CHECK(window_end_inclusive),
 threshold INTEGER NOT NULL DEFAULT 3 CHECK(threshold=3),
 duration_hours INTEGER NOT NULL DEFAULT 168 CHECK(duration_hours=168),
 FOREIGN KEY(restriction_id,user_id,source) REFERENCES restrictions(id,user_id,source),
 UNIQUE(restriction_id,user_id),
 CHECK(window_end=evaluated_at AND window_start=evaluated_at-INTERVAL '720 hours')
);
CREATE TABLE restriction_evaluation_sources (
 restriction_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 violation_id BIGINT NOT NULL UNIQUE,
 source_order INTEGER NOT NULL CHECK(source_order BETWEEN 1 AND 3),
 violation_snapshot JSONB NOT NULL CHECK(jsonb_typeof(violation_snapshot)='object'),
 PRIMARY KEY(restriction_id,source_order),
 FOREIGN KEY(restriction_id,user_id) REFERENCES restriction_evaluations(restriction_id,user_id),
 FOREIGN KEY(violation_id,user_id) REFERENCES violations(id,user_id)
);
-- Deferred so the restriction, evaluation and three sources can be inserted atomically without weakening exact cardinality.
CREATE FUNCTION validate_automatic_restriction_batch() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE batch_id BIGINT; batch_ids BIGINT[]; batch restrictions%ROWTYPE; evaluation restriction_evaluations%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='restrictions' THEN
  IF TG_OP='INSERT' THEN batch_ids=ARRAY[NEW.id]; ELSIF TG_OP='DELETE' THEN batch_ids=ARRAY[OLD.id]; ELSE batch_ids=ARRAY[OLD.id,NEW.id]; END IF;
 ELSE
  IF TG_OP='INSERT' THEN batch_ids=ARRAY[NEW.restriction_id]; ELSIF TG_OP='DELETE' THEN batch_ids=ARRAY[OLD.restriction_id]; ELSE batch_ids=ARRAY[OLD.restriction_id,NEW.restriction_id]; END IF;
 END IF;
 FOREACH batch_id IN ARRAY batch_ids LOOP
 SELECT * INTO batch FROM restrictions WHERE id=batch_id;
 IF NOT FOUND THEN CONTINUE; END IF;
 IF batch.source='automatic' THEN
  SELECT * INTO evaluation FROM restriction_evaluations WHERE restriction_id=batch_id;
  IF NOT FOUND OR evaluation.evaluated_at<>batch.starts_at OR batch.ends_at<>batch.starts_at+INTERVAL '168 hours'
    OR (SELECT count(*) FROM restriction_evaluation_sources WHERE restriction_id=batch_id)<>3 THEN
   RAISE EXCEPTION 'automatic restriction requires its original evaluation and exactly three permanent sources' USING ERRCODE='23514';
  END IF;
 ELSIF EXISTS(SELECT 1 FROM restriction_evaluations WHERE restriction_id=batch_id) THEN
  RAISE EXCEPTION 'only automatic restrictions can own an evaluation' USING ERRCODE='23514';
 END IF;
 END LOOP;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER automatic_restriction_batch_check AFTER INSERT OR UPDATE OR DELETE ON restrictions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_automatic_restriction_batch();
CREATE CONSTRAINT TRIGGER automatic_evaluation_batch_check AFTER INSERT OR UPDATE OR DELETE ON restriction_evaluations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_automatic_restriction_batch();
CREATE CONSTRAINT TRIGGER automatic_sources_batch_check AFTER INSERT OR UPDATE OR DELETE ON restriction_evaluation_sources DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_automatic_restriction_batch();
