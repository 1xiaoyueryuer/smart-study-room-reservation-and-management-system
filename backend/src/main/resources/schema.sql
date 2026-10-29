CREATE TABLE IF NOT EXISTS users (
 id BIGSERIAL PRIMARY KEY, username VARCHAR(50) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
 real_name VARCHAR(50) NOT NULL DEFAULT '未命名', student_no VARCHAR(20) UNIQUE,
 role VARCHAR(20) NOT NULL DEFAULT 'student' CHECK(role IN ('student','admin')),
 credit_score INTEGER NOT NULL DEFAULT 100 CHECK(credit_score BETWEEN 0 AND 100),
 credit_banned_until TIMESTAMP, status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK(status IN ('active','disabled')),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS rooms (
 id BIGSERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE, location VARCHAR(200),
 open_time TIME NOT NULL DEFAULT '08:00', close_time TIME NOT NULL DEFAULT '22:00',
 seat_rows INTEGER NOT NULL CHECK(seat_rows BETWEEN 1 AND 30), seat_cols INTEGER NOT NULL CHECK(seat_cols BETWEEN 1 AND 30),
 description TEXT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, CHECK(close_time>open_time)
);
CREATE TABLE IF NOT EXISTS seats (
 id BIGSERIAL PRIMARY KEY, room_id BIGINT NOT NULL REFERENCES rooms(id), seat_no VARCHAR(20) NOT NULL,
 row_no INTEGER NOT NULL CHECK(row_no>0), col_no INTEGER NOT NULL CHECK(col_no>0),
 zone VARCHAR(30) NOT NULL DEFAULT 'regular' CHECK(zone IN ('quiet','regular','discussion','computer')),
 has_power BOOLEAN NOT NULL DEFAULT FALSE, near_window BOOLEAN NOT NULL DEFAULT FALSE,
 status VARCHAR(20) NOT NULL DEFAULT 'available' CHECK(status IN ('available','maintenance','disabled')),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(room_id,seat_no), UNIQUE(room_id,row_no,col_no)
);
CREATE TABLE IF NOT EXISTS reservations (
 id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id), seat_id BIGINT NOT NULL REFERENCES seats(id),
 res_date DATE NOT NULL, start_time TIME NOT NULL, end_time TIME NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','checked_in','temp_leave','completed','cancelled','violation')),
 source VARCHAR(20) NOT NULL DEFAULT 'manual' CHECK(source IN ('manual','auto','waitlist')),
 checkin_at TIMESTAMP, checkout_at TIMESTAMP, leave_at TIMESTAMP,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(end_time>start_time)
);
CREATE INDEX IF NOT EXISTS idx_res_seat_date ON reservations(seat_id,res_date,status);
CREATE INDEX IF NOT EXISTS idx_res_user_date ON reservations(user_id,res_date,status);
CREATE TABLE IF NOT EXISTS credit_logs (
 id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id), delta INTEGER NOT NULL,
 reason VARCHAR(200) NOT NULL, reservation_id BIGINT REFERENCES reservations(id),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS notifications (
 id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id), type VARCHAR(30) NOT NULL,
 title VARCHAR(200) NOT NULL, content TEXT NOT NULL, is_read BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS waitlist (
 id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id), room_id BIGINT NOT NULL REFERENCES rooms(id),
 res_date DATE NOT NULL, start_time TIME NOT NULL, end_time TIME NOT NULL, zone VARCHAR(30), has_power BOOLEAN, near_window BOOLEAN,
 status VARCHAR(20) NOT NULL DEFAULT 'waiting' CHECK(status IN ('waiting','promoted','cancelled','expired')),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(end_time>start_time)
);
-- 所有预约与资源写事务先锁此行，再检查冲突。无需安装额外扩展。
CREATE TABLE IF NOT EXISTS booking_mutex (id INTEGER PRIMARY KEY);
INSERT INTO booking_mutex(id) SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM booking_mutex WHERE id=1);
