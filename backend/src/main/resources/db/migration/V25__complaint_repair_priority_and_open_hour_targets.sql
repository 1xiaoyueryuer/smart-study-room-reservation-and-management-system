-- V1.1 complaint branches: old service rows remain explicitly unclassified.
CREATE TABLE complaint_priority_mapping_versions (
 version BIGINT PRIMARY KEY CHECK(version>0),
 mappings JSONB NOT NULL CHECK(jsonb_typeof(mappings)='array'),
 operation_id UUID UNIQUE,
 request_hash VARCHAR(64),
 actor_id BIGINT REFERENCES users(id),
 reason VARCHAR(1000) NOT NULL CHECK(char_length(trim(reason)) BETWEEN 1 AND 1000),
 created_at TIMESTAMP NOT NULL,
 CHECK((version=1 AND operation_id IS NULL AND request_hash IS NULL AND actor_id IS NULL) OR
       (version>1 AND operation_id IS NOT NULL AND request_hash ~ '^[0-9a-f]{64}$' AND actor_id IS NOT NULL))
);
INSERT INTO complaint_priority_mapping_versions(version,mappings,reason,created_at)
 VALUES(1,'[]'::jsonb,'V1.1 O-08映射值尚待小组确认；初始规则集留空，不推定故障类型与影响范围组合',CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai');

ALTER TABLE complaints
 ADD COLUMN service_kind VARCHAR(30),
 ADD COLUMN service_topic VARCHAR(100),
 ADD COLUMN fault_type VARCHAR(100),
 ADD COLUMN impact_scope VARCHAR(100),
 ADD COLUMN safety_risk BOOLEAN,
 ADD COLUMN unsafe_area BOOLEAN,
 ADD COLUMN initial_priority SMALLINT,
 ADD COLUMN priority_status VARCHAR(30),
 ADD COLUMN priority_mapping_version BIGINT REFERENCES complaint_priority_mapping_versions(version),
 ADD COLUMN triaged_priority SMALLINT,
 ADD COLUMN triaged_safety_risk BOOLEAN,
 ADD COLUMN triaged_unsafe_area BOOLEAN,
 ADD COLUMN triaged_reason VARCHAR(1000),
 ADD COLUMN triaged_at TIMESTAMP;
ALTER TABLE complaints ADD CONSTRAINT complaint_service_kind_check CHECK(service_kind IS NULL OR service_kind IN('facility_repair','other_service'));
ALTER TABLE complaints ADD CONSTRAINT complaint_service_details_check CHECK(
 (type='occupation' AND service_kind IS NULL AND service_topic IS NULL AND fault_type IS NULL AND impact_scope IS NULL AND safety_risk IS NULL AND unsafe_area IS NULL AND initial_priority IS NULL AND priority_status IS NULL AND priority_mapping_version IS NULL AND triaged_priority IS NULL AND triaged_safety_risk IS NULL AND triaged_unsafe_area IS NULL AND triaged_reason IS NULL AND triaged_at IS NULL) OR
 (type='service' AND service_kind IS NULL AND service_topic IS NULL AND fault_type IS NULL AND impact_scope IS NULL AND safety_risk IS NULL AND unsafe_area IS NULL AND initial_priority IS NULL AND priority_status IS NULL AND priority_mapping_version IS NULL AND triaged_priority IS NULL AND triaged_safety_risk IS NULL AND triaged_unsafe_area IS NULL AND triaged_reason IS NULL AND triaged_at IS NULL) OR
 (type='service' AND service_kind='facility_repair' AND room_id IS NOT NULL AND service_topic IS NULL AND char_length(trim(fault_type)) BETWEEN 1 AND 100 AND char_length(trim(impact_scope)) BETWEEN 1 AND 100 AND safety_risk IS NOT NULL AND unsafe_area IS NOT NULL AND priority_status IS NOT NULL AND priority_status IN('mapped','safety_override','unconfigured') AND priority_mapping_version IS NOT NULL AND
   ((priority_status='unconfigured' AND initial_priority IS NULL AND NOT safety_risk AND NOT unsafe_area) OR
    (priority_status='mapped' AND initial_priority BETWEEN 1 AND 4 AND NOT safety_risk AND NOT unsafe_area) OR
    (priority_status='safety_override' AND initial_priority=5 AND (safety_risk OR unsafe_area))) AND
   ((status IN('submitted','withdrawn') AND triaged_priority IS NULL AND triaged_safety_risk IS NULL AND triaged_unsafe_area IS NULL AND triaged_reason IS NULL AND triaged_at IS NULL) OR
    (status NOT IN('submitted','withdrawn') AND triaged_priority IS NOT NULL AND triaged_priority BETWEEN 1 AND 5 AND triaged_safety_risk IS NOT NULL AND triaged_unsafe_area IS NOT NULL AND char_length(trim(triaged_reason)) BETWEEN 1 AND 1000 AND triaged_at IS NOT NULL AND (NOT safety_risk OR triaged_safety_risk) AND (NOT unsafe_area OR triaged_unsafe_area) AND ((triaged_safety_risk OR triaged_unsafe_area) AND triaged_priority=5 OR (NOT triaged_safety_risk AND NOT triaged_unsafe_area) AND triaged_priority BETWEEN 1 AND 4)))) OR
 (type='service' AND service_kind='other_service' AND room_id IS NOT NULL AND char_length(trim(service_topic)) BETWEEN 1 AND 100 AND fault_type IS NULL AND impact_scope IS NULL AND safety_risk IS NULL AND unsafe_area IS NULL AND initial_priority IS NULL AND priority_status IS NULL AND priority_mapping_version IS NULL AND triaged_priority IS NULL AND triaged_safety_risk IS NULL AND triaged_unsafe_area IS NULL AND triaged_reason IS NULL AND triaged_at IS NULL)
);

ALTER TABLE complaint_events
 ADD COLUMN triage_priority SMALLINT,
 ADD COLUMN triage_safety_risk BOOLEAN,
 ADD COLUMN triage_unsafe_area BOOLEAN,
 ADD COLUMN triage_reason VARCHAR(1000);
ALTER TABLE complaint_events ADD CONSTRAINT complaint_event_triage_check CHECK(
 (triage_priority IS NULL AND triage_safety_risk IS NULL AND triage_unsafe_area IS NULL AND triage_reason IS NULL) OR
 (event_type='accepted' AND triage_priority IS NOT NULL AND triage_priority BETWEEN 1 AND 5 AND triage_safety_risk IS NOT NULL AND triage_unsafe_area IS NOT NULL AND triage_reason IS NOT NULL AND char_length(trim(triage_reason)) BETWEEN 1 AND 1000 AND
   ((triage_safety_risk OR triage_unsafe_area) AND triage_priority=5 OR (NOT triage_safety_risk AND NOT triage_unsafe_area) AND triage_priority BETWEEN 1 AND 4))
);

-- A typed priority notification links to the real immutable accepted event.
ALTER TABLE notifications DROP CONSTRAINT notification_actual_complaint_event_fk;
ALTER TABLE notifications DROP CONSTRAINT notification_complaint_event_complete;
ALTER TABLE notifications DROP CONSTRAINT notification_new_event_typed;
ALTER TABLE notifications DROP COLUMN complaint_event_type;
ALTER TABLE notifications ADD COLUMN complaint_event_type VARCHAR(30) GENERATED ALWAYS AS (
 CASE WHEN complaint_event_id IS NULL THEN NULL
      WHEN type='complaint_feedback' THEN 'feedback'
      WHEN type='complaint_auto_closed' THEN 'auto_closed'
      WHEN type='complaint_priority_assessed' THEN 'accepted' END) STORED;
ALTER TABLE notifications ADD CONSTRAINT notification_actual_complaint_event_fk FOREIGN KEY(complaint_event_id,complaint_id,complaint_event_type) REFERENCES complaint_events(id,complaint_id,event_type);
ALTER TABLE notifications ADD CONSTRAINT notification_complaint_event_complete CHECK(complaint_event_id IS NULL OR (complaint_id IS NOT NULL AND type IN('complaint_feedback','complaint_auto_closed','complaint_priority_assessed')));
ALTER TABLE notifications ADD CONSTRAINT notification_new_event_typed CHECK(event_key IS NULL OR
 (type IN('reservation_success','reservation_cancelled','reservation_checkin','reservation_completed','management_cancelled','restriction_cancelled','reservation_corrected_checkin') AND reservation_id IS NOT NULL) OR
 (type='violation' AND violation_id IS NOT NULL) OR
 (type IN('restriction_started','restriction_released') AND restriction_id IS NOT NULL) OR
 (type IN('complaint_feedback','complaint_auto_closed','complaint_priority_assessed') AND complaint_id IS NOT NULL AND complaint_event_id IS NOT NULL) OR
 (type='appeal_reviewed' AND appeal_id IS NOT NULL) OR
 (type IN('review_replied','review_hidden') AND review_reservation_id IS NOT NULL AND review_operation_id IS NOT NULL));

-- Preserve S17 historical wall-clock target facts; only new service targets use the new basis.
ALTER TABLE complaint_targets ADD COLUMN target_seconds BIGINT;
ALTER TABLE complaint_targets ADD COLUMN open_intervals JSONB;
ALTER TABLE complaint_targets ADD COLUMN room_calendar_snapshot JSONB;
UPDATE complaint_targets SET target_seconds=CASE WHEN basis IN('service_24h','legacy_service_derived') THEN 86400 ELSE 900 END;
ALTER TABLE complaint_targets ALTER COLUMN target_seconds SET NOT NULL;
ALTER TABLE complaint_targets DROP CONSTRAINT complaint_targets_kind_check;
ALTER TABLE complaint_targets DROP CONSTRAINT complaint_targets_calculation_status_check;
ALTER TABLE complaint_targets DROP CONSTRAINT complaint_targets_basis_check;
ALTER TABLE complaint_targets DROP CONSTRAINT complaint_targets_source_check;
ALTER TABLE complaint_targets DROP CONSTRAINT complaint_targets_reason_check;
ALTER TABLE complaint_targets DROP CONSTRAINT complaint_targets_room_version_check;
ALTER TABLE complaint_targets DROP CONSTRAINT complaint_targets_check;
ALTER TABLE complaint_targets DROP CONSTRAINT complaint_targets_check1;
ALTER TABLE complaint_targets
 ADD CONSTRAINT complaint_target_kind_check CHECK(kind IN('acceptance','first_feedback')),
 ADD CONSTRAINT complaint_target_status_check CHECK(calculation_status IN('calculated','pending','legacy_unresolved')),
 ADD CONSTRAINT complaint_target_basis_check CHECK(basis IN('open_at_submission','next_room_opening','service_24h','legacy_service_derived','legacy_occupation_unresolved','service_open_hours_24')),
 ADD CONSTRAINT complaint_target_source_check CHECK(source IN('new_submission','legacy_derived','legacy_unresolved')),
 ADD CONSTRAINT complaint_target_reason_check CHECK(char_length(trim(reason)) BETWEEN 1 AND 1000),
 ADD CONSTRAINT complaint_target_room_version_check CHECK(room_version IS NULL OR room_version>0),
 ADD CONSTRAINT complaint_target_seconds_check CHECK(target_seconds IN(900,86400)),
 ADD CONSTRAINT complaint_target_intervals_check CHECK(open_intervals IS NULL OR jsonb_typeof(open_intervals)='array'),
 ADD CONSTRAINT complaint_target_temporal_check CHECK(
   (calculation_status='calculated' AND anchor_at IS NOT NULL AND due_at IS NOT NULL AND due_at>=anchor_at) OR
   (calculation_status='pending' AND due_at IS NULL) OR
   (calculation_status='legacy_unresolved' AND anchor_at IS NULL AND due_at IS NULL)),
 ADD CONSTRAINT complaint_target_domain_check CHECK(
   (kind='first_feedback' AND calculation_status='calculated' AND room_version IS NULL AND open_intervals IS NULL AND target_seconds=86400 AND
     ((basis='service_24h' AND source='new_submission' AND due_at=anchor_at+INTERVAL '24 hours') OR
      (basis='legacy_service_derived' AND source='legacy_derived' AND due_at=anchor_at+INTERVAL '24 hours'))) OR
   (kind='acceptance' AND source='new_submission' AND room_version IS NOT NULL AND
     ((basis IN('open_at_submission','next_room_opening') AND target_seconds=900 AND open_intervals IS NULL AND
        ((calculation_status='calculated' AND due_at=anchor_at+INTERVAL '15 minutes') OR calculation_status='pending')) OR
      (basis='service_open_hours_24' AND target_seconds=86400 AND open_intervals IS NOT NULL AND room_calendar_snapshot IS NOT NULL AND anchor_at IS NOT NULL AND
        calculation_status IN('calculated','pending') AND (calculation_status<>'calculated' OR due_at IS NOT NULL)))) OR
   (kind='acceptance' AND calculation_status='legacy_unresolved' AND basis='legacy_occupation_unresolved' AND source='legacy_unresolved' AND target_seconds=900 AND room_version IS NULL AND open_intervals IS NULL));

CREATE INDEX idx_complaint_priority_mapping_latest ON complaint_priority_mapping_versions(version DESC);

CREATE FUNCTION prevent_complaint_priority_mapping_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'complaint priority mapping versions are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER complaint_priority_mapping_immutable BEFORE UPDATE OR DELETE ON complaint_priority_mapping_versions
 FOR EACH ROW EXECUTE FUNCTION prevent_complaint_priority_mapping_mutation();
