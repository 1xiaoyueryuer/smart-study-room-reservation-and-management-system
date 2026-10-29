-- Transfer only remaining legacy validity. Original credit fields/logs and past reservation/notification facts stay untouched.
CREATE TABLE restrictions (
 id BIGSERIAL PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES users(id),
 source VARCHAR(20) NOT NULL CHECK(source IN('manual','legacy')),
 starts_at TIMESTAMP NOT NULL,
 ends_at TIMESTAMP NOT NULL,
 reason VARCHAR(1000) NOT NULL CHECK(char_length(trim(reason)) BETWEEN 1 AND 1000),
 creator_id BIGINT REFERENCES users(id),
 verification_note VARCHAR(2000),
 facts_confirmed BOOLEAN NOT NULL DEFAULT false,
 created_at TIMESTAMP NOT NULL,
 effects_applied_at TIMESTAMP,
 version BIGINT NOT NULL DEFAULT 1 CHECK(version>0),
 released_at TIMESTAMP,
 release_reason VARCHAR(1000),
 releaser_id BIGINT REFERENCES users(id),
 release_verification_note VARCHAR(2000),
 release_facts_confirmed BOOLEAN NOT NULL DEFAULT false,
 UNIQUE(id,user_id),
 CHECK(ends_at>starts_at AND created_at=starts_at),
 CHECK(effects_applied_at IS NULL OR (effects_applied_at>=starts_at AND effects_applied_at<ends_at)),
 CHECK((source='manual' AND creator_id IS NOT NULL AND facts_confirmed AND verification_note IS NOT NULL AND char_length(trim(verification_note)) BETWEEN 1 AND 2000) OR (source='legacy' AND creator_id IS NULL AND NOT facts_confirmed AND verification_note IS NULL)),
 CHECK((released_at IS NULL AND release_reason IS NULL AND releaser_id IS NULL AND release_verification_note IS NULL AND NOT release_facts_confirmed) OR (released_at IS NOT NULL AND released_at>=starts_at AND released_at<ends_at AND release_reason IS NOT NULL AND char_length(trim(release_reason)) BETWEEN 1 AND 1000 AND releaser_id IS NOT NULL AND release_verification_note IS NOT NULL AND char_length(trim(release_verification_note)) BETWEEN 1 AND 2000 AND release_facts_confirmed))
);
CREATE UNIQUE INDEX idx_legacy_restriction_user ON restrictions(user_id) WHERE source='legacy';
CREATE INDEX idx_active_restrictions_user ON restrictions(user_id,ends_at) WHERE released_at IS NULL;
CREATE TABLE restriction_operations (
 operation_id UUID PRIMARY KEY,
 restriction_id BIGINT NOT NULL REFERENCES restrictions(id),
 actor_id BIGINT NOT NULL REFERENCES users(id),
 action VARCHAR(20) NOT NULL CHECK(action IN('create','release')),
 request_hash VARCHAR(64) NOT NULL,
 effects JSONB NOT NULL CHECK(jsonb_typeof(effects)='object'),
 created_at TIMESTAMP NOT NULL,
 UNIQUE(restriction_id,action)
);
ALTER TABLE notifications ADD COLUMN restriction_id BIGINT;
ALTER TABLE notifications ADD CONSTRAINT notification_restriction_owner_fk FOREIGN KEY(restriction_id,user_id) REFERENCES restrictions(id,user_id);
ALTER TABLE notifications ADD CONSTRAINT notification_restriction_type_check CHECK(restriction_id IS NULL OR (type IN('restriction_started','restriction_released','restriction_cancelled') AND violation_id IS NULL AND complaint_id IS NULL));
CREATE UNIQUE INDEX idx_restriction_transition_notification ON notifications(restriction_id,type) WHERE restriction_id IS NOT NULL AND type IN('restriction_started','restriction_released');
WITH cutover AS (SELECT CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai' AS at)
INSERT INTO restrictions(user_id,source,starts_at,ends_at,reason,creator_id,verification_note,facts_confirmed,created_at)
SELECT u.id,'legacy',c.at,u.credit_banned_until,'旧限制剩余有效期接管：原始起点与原因未可靠记录，保留原截止；当前显示起点仅为本次接管时刻',NULL,NULL,false,c.at
FROM users u CROSS JOIN cutover c WHERE u.credit_banned_until IS NOT NULL AND u.credit_banned_until>c.at;
