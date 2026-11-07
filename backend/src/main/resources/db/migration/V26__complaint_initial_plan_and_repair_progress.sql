-- Append-only V1.1 acceptance plan and existing-channel repair handoff/progress facts.
ALTER TABLE complaint_events
 ADD COLUMN initial_arrangement VARCHAR(1000),
 ADD COLUMN service_progress_stage VARCHAR(20),
 ADD COLUMN maintenance_channel VARCHAR(150),
 ADD COLUMN handoff_at TIMESTAMP;
ALTER TABLE complaint_events ADD CONSTRAINT complaint_event_initial_arrangement_check CHECK(
 initial_arrangement IS NULL OR (event_type='accepted' AND char_length(trim(initial_arrangement)) BETWEEN 1 AND 1000));

-- Avoid SQL CHECK UNKNOWN accepting NULL service fields on V25's OR branches.
ALTER TABLE complaints ADD CONSTRAINT complaint_service_fields_explicit_v26 CHECK(
 (type='occupation' AND service_kind IS NULL AND service_topic IS NULL AND fault_type IS NULL AND impact_scope IS NULL AND safety_risk IS NULL AND unsafe_area IS NULL) OR
 (type='service' AND service_kind IS NULL AND service_topic IS NULL AND fault_type IS NULL AND impact_scope IS NULL AND safety_risk IS NULL AND unsafe_area IS NULL) OR
 (type='service' AND service_kind='facility_repair' AND room_id IS NOT NULL AND fault_type IS NOT NULL AND char_length(trim(fault_type)) BETWEEN 1 AND 100 AND impact_scope IS NOT NULL AND char_length(trim(impact_scope)) BETWEEN 1 AND 100 AND safety_risk IS NOT NULL AND unsafe_area IS NOT NULL AND priority_mapping_version IS NOT NULL AND priority_status IS NOT NULL AND
   ((priority_status='unconfigured' AND initial_priority IS NULL AND NOT safety_risk AND NOT unsafe_area) OR
    (priority_status='mapped' AND initial_priority BETWEEN 1 AND 4 AND NOT safety_risk AND NOT unsafe_area) OR
    (priority_status='safety_override' AND initial_priority=5 AND (safety_risk OR unsafe_area))) AND
   ((status IN('submitted','withdrawn') AND triaged_priority IS NULL AND triaged_safety_risk IS NULL AND triaged_unsafe_area IS NULL AND triaged_reason IS NULL AND triaged_at IS NULL) OR
    (status NOT IN('submitted','withdrawn') AND triaged_priority IS NOT NULL AND triaged_priority BETWEEN 1 AND 5 AND triaged_safety_risk IS NOT NULL AND triaged_unsafe_area IS NOT NULL AND triaged_reason IS NOT NULL AND char_length(trim(triaged_reason)) BETWEEN 1 AND 1000 AND triaged_at IS NOT NULL))) OR
 (type='service' AND service_kind='other_service' AND room_id IS NOT NULL AND service_topic IS NOT NULL AND char_length(trim(service_topic)) BETWEEN 1 AND 100 AND fault_type IS NULL AND impact_scope IS NULL AND safety_risk IS NULL AND unsafe_area IS NULL AND initial_priority IS NULL AND priority_status IS NULL AND priority_mapping_version IS NULL AND triaged_priority IS NULL AND triaged_safety_risk IS NULL AND triaged_unsafe_area IS NULL AND triaged_reason IS NULL AND triaged_at IS NULL));

ALTER TABLE complaint_events DROP CONSTRAINT complaint_events_event_type_check;
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_event_type_check CHECK(event_type IN('submitted','accepted','verification','feedback','withdrawn','confirmed','objected','auto_closed','service_progress'));
ALTER TABLE complaint_events DROP CONSTRAINT complaint_events_operation_check;
ALTER TABLE complaint_events ADD CONSTRAINT complaint_events_operation_check CHECK(
 (event_type='submitted' AND source='user_submit' AND actor_id IS NOT NULL AND operation_id IS NULL AND request_hash IS NULL) OR
 (event_type IN('withdrawn','confirmed','objected') AND source='user_submit' AND actor_id IS NOT NULL AND operation_id IS NOT NULL AND request_hash IS NOT NULL) OR
 (event_type IN('accepted','verification','feedback','service_progress') AND source='admin_self_report' AND actor_id IS NOT NULL AND operation_id IS NOT NULL AND request_hash IS NOT NULL) OR
 (event_type='auto_closed' AND source='system' AND actor_id IS NULL AND operation_id IS NULL AND request_hash IS NULL));
ALTER TABLE complaint_events ADD CONSTRAINT complaint_event_service_progress_check CHECK(
 (event_type<>'service_progress' AND service_progress_stage IS NULL AND maintenance_channel IS NULL AND handoff_at IS NULL) OR
 (event_type='service_progress' AND service_progress_stage IS NOT NULL AND service_progress_stage IN('handoff','progress') AND maintenance_channel IS NOT NULL AND char_length(trim(maintenance_channel)) BETWEEN 1 AND 150 AND handoff_at IS NOT NULL AND handoff_at<=created_at AND occurred_at IS NULL AND public_note IS NOT NULL AND char_length(trim(public_note)) BETWEEN 1 AND 1000));
CREATE UNIQUE INDEX idx_complaint_repair_handoff_once ON complaint_events(complaint_id) WHERE event_type='service_progress' AND service_progress_stage='handoff';

CREATE FUNCTION validate_complaint_service_progress_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE service_kind_value TEXT; complaint_status_value TEXT; accepted_at_value TIMESTAMP; handoff_channel_value TEXT; handoff_time_value TIMESTAMP;
BEGIN
 IF NEW.event_type<>'service_progress' THEN RETURN NEW; END IF;
 SELECT service_kind,status,accepted_at INTO service_kind_value,complaint_status_value,accepted_at_value FROM complaints WHERE id=NEW.complaint_id;
 IF service_kind_value IS DISTINCT FROM 'facility_repair' OR complaint_status_value IS DISTINCT FROM 'processing' THEN RAISE EXCEPTION 'service progress requires a processing facility repair' USING ERRCODE='23514'; END IF;
 IF NEW.service_progress_stage='handoff' THEN
  IF NEW.handoff_at<accepted_at_value THEN RAISE EXCEPTION 'repair handoff cannot precede acceptance' USING ERRCODE='23514'; END IF;
 ELSIF NEW.service_progress_stage='progress' THEN
  SELECT maintenance_channel,handoff_at INTO handoff_channel_value,handoff_time_value FROM complaint_events WHERE complaint_id=NEW.complaint_id AND event_type='service_progress' AND service_progress_stage='handoff';
  IF handoff_channel_value IS NULL OR NEW.maintenance_channel<>handoff_channel_value OR NEW.handoff_at<>handoff_time_value THEN RAISE EXCEPTION 'repair progress must preserve its original handoff' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER complaint_service_progress_validate BEFORE INSERT ON complaint_events FOR EACH ROW EXECUTE FUNCTION validate_complaint_service_progress_event();
ALTER FUNCTION validate_complaint_service_progress_event() SET search_path FROM CURRENT;

-- A real progress notification is bound to the immutable service_progress event.
ALTER TABLE notifications DROP CONSTRAINT notification_actual_complaint_event_fk;
ALTER TABLE notifications DROP CONSTRAINT notification_complaint_event_complete;
ALTER TABLE notifications DROP CONSTRAINT notification_new_event_typed;
ALTER TABLE notifications DROP COLUMN complaint_event_type;
ALTER TABLE notifications ADD COLUMN complaint_event_type VARCHAR(30) GENERATED ALWAYS AS (
 CASE WHEN complaint_event_id IS NULL THEN NULL
      WHEN type='complaint_feedback' THEN 'feedback'
      WHEN type='complaint_auto_closed' THEN 'auto_closed'
      WHEN type='complaint_initial_arrangement' THEN 'accepted'
      WHEN type='complaint_priority_assessed' THEN 'accepted'
      WHEN type='complaint_service_progress' THEN 'service_progress' END) STORED;
ALTER TABLE notifications ADD CONSTRAINT notification_actual_complaint_event_fk FOREIGN KEY(complaint_event_id,complaint_id,complaint_event_type) REFERENCES complaint_events(id,complaint_id,event_type);
ALTER TABLE notifications ADD CONSTRAINT notification_complaint_event_complete CHECK(complaint_event_id IS NULL OR (complaint_id IS NOT NULL AND type IN('complaint_feedback','complaint_auto_closed','complaint_initial_arrangement','complaint_priority_assessed','complaint_service_progress')));
ALTER TABLE notifications ADD CONSTRAINT notification_new_event_typed CHECK(event_key IS NULL OR
 (type IN('reservation_success','reservation_cancelled','reservation_checkin','reservation_completed','management_cancelled','restriction_cancelled','reservation_corrected_checkin') AND reservation_id IS NOT NULL) OR
 (type='violation' AND violation_id IS NOT NULL) OR
 (type IN('restriction_started','restriction_released') AND restriction_id IS NOT NULL) OR
 (type IN('complaint_feedback','complaint_auto_closed','complaint_initial_arrangement','complaint_priority_assessed','complaint_service_progress') AND complaint_id IS NOT NULL AND complaint_event_id IS NOT NULL) OR
 (type='appeal_reviewed' AND appeal_id IS NOT NULL) OR
 (type IN('review_replied','review_hidden') AND review_reservation_id IS NOT NULL AND review_operation_id IS NOT NULL));
