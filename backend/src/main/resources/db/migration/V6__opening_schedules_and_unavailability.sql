-- 不把真实历史时间默默截断为新模型的分钟值。
DO $guard$
BEGIN
 IF EXISTS (SELECT 1 FROM rooms WHERE EXTRACT(SECOND FROM open_time)<>0 OR EXTRACT(SECOND FROM close_time)<>0) THEN
  RAISE EXCEPTION '旧每日开放时间存在非整分钟值，拒绝V6迁移；请核对原值并明确处理，禁止静默截断';
 END IF;
END
$guard$;
CREATE TABLE opening_schedules (
 id BIGSERIAL PRIMARY KEY,
 room_id BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
 weekday INTEGER CHECK(weekday BETWEEN 1 AND 7),
 schedule_date DATE,
 reason VARCHAR(1000) NOT NULL,
 CHECK ((weekday IS NULL) <> (schedule_date IS NULL)),
 UNIQUE(room_id,weekday), UNIQUE(room_id,schedule_date)
);
CREATE TABLE opening_windows (
 id BIGSERIAL PRIMARY KEY,
 schedule_id BIGINT NOT NULL REFERENCES opening_schedules(id) ON DELETE CASCADE,
 start_minute INTEGER NOT NULL CHECK(start_minute BETWEEN 0 AND 1439),
 end_minute INTEGER NOT NULL CHECK(end_minute BETWEEN 1 AND 1440),
 CHECK(end_minute>start_minute), UNIQUE(schedule_id,start_minute)
);
INSERT INTO opening_schedules(room_id,weekday,reason)
 SELECT r.id,d,'原每日开放安排迁移' FROM rooms r CROSS JOIN generate_series(1,7) d;
INSERT INTO opening_windows(schedule_id,start_minute,end_minute)
 SELECT o.id,EXTRACT(EPOCH FROM r.open_time)::integer/60,EXTRACT(EPOCH FROM r.close_time)::integer/60
 FROM opening_schedules o JOIN rooms r ON r.id=o.room_id;
CREATE TABLE resource_unavailability (
 id BIGSERIAL PRIMARY KEY,
 room_id BIGINT NOT NULL REFERENCES rooms(id),
 seat_id BIGINT REFERENCES seats(id),
 start_at TIMESTAMP NOT NULL, end_at TIMESTAMP NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 actor_id BIGINT NOT NULL REFERENCES users(id),
 version BIGINT NOT NULL DEFAULT 1,
 cancelled BOOLEAN NOT NULL DEFAULT FALSE,
 cancel_reason VARCHAR(1000), cancelled_at TIMESTAMP,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(end_at>start_at)
);
CREATE INDEX idx_unavailability_room_time ON resource_unavailability(room_id,start_at,end_at) WHERE NOT cancelled;
