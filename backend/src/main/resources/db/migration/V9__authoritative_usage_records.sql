-- 在任何DDL之前诊断矛盾使用事实，不以当前时间补造缺失历史。
DO $diagnostic$
DECLARE bad_id BIGINT; category TEXT; cutover TIMESTAMP := CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai';
BEGIN
 SELECT id,problem INTO bad_id,category FROM (
  SELECT id,CASE
   WHEN status='completed' AND (checkin_at IS NULL OR checkout_at IS NULL) THEN 'completed_missing_time'
   WHEN status IN('checked_in','temp_leave') AND checkin_at IS NULL THEN 'active_missing_checkin'
   WHEN status IN('checked_in','temp_leave') AND checkout_at IS NOT NULL THEN 'active_has_checkout'
   WHEN checkout_at IS NOT NULL AND checkin_at IS NULL THEN 'checkout_without_checkin'
   WHEN status NOT IN('completed','checked_in','temp_leave') AND (checkin_at IS NOT NULL OR checkout_at IS NOT NULL) THEN 'state_time_conflict'
   WHEN checkout_at<checkin_at THEN 'checkout_before_checkin'
   WHEN status IN('checked_in','temp_leave') AND (ended_at IS NOT NULL OR termination_reason IS NOT NULL) THEN 'active_has_termination'
   WHEN status='completed' AND ended_at IS NOT NULL AND ended_at<>LEAST(checkout_at,res_date+end_time) THEN 'ended_checkout_conflict'
   WHEN status='completed' AND termination_reason IS NOT NULL AND termination_reason NOT IN('early_checkout','auto_end','temp_leave_timeout') THEN 'unknown_termination_reason'
   WHEN checkin_at>=res_date+end_time THEN 'checkin_not_before_scheduled_end'
   WHEN status IN('checked_in','temp_leave') AND checkin_at>cutover THEN 'active_future_checkin'
   WHEN start_time>=end_time THEN 'invalid_planned_interval'
  END AS problem FROM reservations
 ) diagnosed WHERE problem IS NOT NULL ORDER BY id LIMIT 1;
 IF bad_id IS NOT NULL THEN RAISE EXCEPTION 'V9 usage migration rejected reservation %: %',bad_id,category; END IF;
END
$diagnostic$;

CREATE TABLE usage_records (
 id BIGSERIAL PRIMARY KEY,
 reservation_id BIGINT NOT NULL UNIQUE REFERENCES reservations(id),
 checkin_at TIMESTAMP NOT NULL,
 ended_at TIMESTAMP,
 end_reason VARCHAR(40),
 settled_at TIMESTAMP,
 origin VARCHAR(20) NOT NULL CHECK(origin IN('legacy','seat_code','demo_seed')),
 legacy_checkout_at TIMESTAMP,
 legacy_termination_reason VARCHAR(40),
 created_at TIMESTAMP NOT NULL,
 updated_at TIMESTAMP NOT NULL,
 CHECK(ended_at IS NULL OR ended_at>=checkin_at),
 CHECK((ended_at IS NULL AND end_reason IS NULL AND settled_at IS NULL) OR (ended_at IS NOT NULL AND end_reason IS NOT NULL AND settled_at IS NOT NULL))
);

INSERT INTO usage_records(reservation_id,checkin_at,ended_at,end_reason,settled_at,origin,legacy_checkout_at,legacy_termination_reason,created_at,updated_at)
SELECT id,checkin_at,
 CASE WHEN checkout_at IS NOT NULL THEN LEAST(checkout_at,res_date+end_time) WHEN res_date+end_time<=CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai' THEN res_date+end_time END,
 CASE WHEN checkout_at IS NOT NULL THEN COALESCE(termination_reason,'legacy_unclassified') WHEN res_date+end_time<=CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai' THEN 'auto_end' END,
 CASE WHEN checkout_at IS NOT NULL OR res_date+end_time<=CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai' THEN CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai' END,
 'legacy',checkout_at,termination_reason,CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai',CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai'
FROM reservations WHERE checkin_at IS NOT NULL;

UPDATE reservations SET legacy_status=COALESCE(legacy_status,status) WHERE status='temp_leave';
UPDATE reservations r SET status=CASE WHEN u.ended_at IS NULL THEN 'checked_in' ELSE 'completed' END,
 ended_at=u.ended_at,termination_reason=u.end_reason
FROM usage_records u WHERE u.reservation_id=r.id;
ALTER TABLE reservations DROP CONSTRAINT reservations_status_check;
ALTER TABLE reservations ADD CONSTRAINT reservations_status_check CHECK(status IN('pending','checked_in','completed','cancelled','unused_ended'));
