-- Immutable submitted target snapshots; original lifecycle events remain the authority for completion and withdrawal.
CREATE TABLE complaint_targets (
 complaint_id BIGINT PRIMARY KEY REFERENCES complaints(id),
 kind VARCHAR(30) NOT NULL CHECK(kind IN('acceptance','first_feedback')),
 calculation_status VARCHAR(30) NOT NULL CHECK(calculation_status IN('calculated','pending','legacy_unresolved')),
 anchor_at TIMESTAMP,
 due_at TIMESTAMP,
 reason VARCHAR(1000) NOT NULL CHECK(char_length(trim(reason))>0),
 room_version BIGINT CHECK(room_version>0),
 calculated_at TIMESTAMP NOT NULL,
 basis VARCHAR(40) NOT NULL CHECK(basis IN('open_at_submission','next_room_opening','service_24h','legacy_service_derived','legacy_occupation_unresolved')),
 source VARCHAR(30) NOT NULL CHECK(source IN('new_submission','legacy_derived','legacy_unresolved')),
 UNIQUE(complaint_id,due_at,source),
 CHECK((calculation_status='calculated' AND anchor_at IS NOT NULL AND due_at IS NOT NULL AND due_at=anchor_at+CASE WHEN kind='acceptance' THEN INTERVAL '15 minutes' ELSE INTERVAL '24 hours' END) OR (calculation_status IN('pending','legacy_unresolved') AND anchor_at IS NULL AND due_at IS NULL)),
 CHECK((kind='first_feedback' AND calculation_status='calculated' AND room_version IS NULL AND ((basis='service_24h' AND source='new_submission') OR (basis='legacy_service_derived' AND source='legacy_derived'))) OR
       (kind='acceptance' AND source='new_submission' AND room_version IS NOT NULL AND ((calculation_status='calculated' AND basis IN('open_at_submission','next_room_opening')) OR (calculation_status='pending' AND basis='next_room_opening'))) OR
       (kind='acceptance' AND calculation_status='legacy_unresolved' AND basis='legacy_occupation_unresolved' AND source='legacy_unresolved' AND room_version IS NULL))
);
CREATE INDEX idx_complaint_targets_due ON complaint_targets(due_at) WHERE due_at IS NOT NULL;
CREATE TABLE complaint_target_events (
 id BIGSERIAL PRIMARY KEY,
 complaint_id BIGINT NOT NULL UNIQUE,
 occurred_at TIMESTAMP NOT NULL,
 detected_at TIMESTAMP NOT NULL,
 source VARCHAR(30) NOT NULL CHECK(source IN('new_submission','legacy_derived')),
 CHECK(detected_at>=occurred_at),
 FOREIGN KEY(complaint_id,occurred_at,source) REFERENCES complaint_targets(complaint_id,due_at,source)
);
INSERT INTO complaint_targets(complaint_id,kind,calculation_status,anchor_at,due_at,reason,room_version,calculated_at,basis,source)
 SELECT id,CASE WHEN type='service' THEN 'first_feedback' ELSE 'acceptance' END,
 CASE WHEN type='service' THEN 'calculated' ELSE 'legacy_unresolved' END,
 CASE WHEN type='service' THEN submitted_at ELSE NULL END,
 CASE WHEN type='service' THEN submitted_at+INTERVAL '24 hours' ELSE NULL END,
 CASE WHEN type='service' THEN '旧一般服务投诉按原提交时刻推导24小时首次正式反馈目标；迁移不补造历史检测事件' ELSE '旧被占投诉缺少提交时的完整开放快照，无法可靠重建受理目标；不使用当前资源反推历史' END,
 NULL,CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai',
 CASE WHEN type='service' THEN 'legacy_service_derived' ELSE 'legacy_occupation_unresolved' END,
 CASE WHEN type='service' THEN 'legacy_derived' ELSE 'legacy_unresolved' END FROM complaints;
