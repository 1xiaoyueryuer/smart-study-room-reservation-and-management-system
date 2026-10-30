ALTER TABLE reservations ADD CONSTRAINT reservation_id_owner_unique UNIQUE(id,user_id);
CREATE TABLE reviews (
 id BIGSERIAL PRIMARY KEY,
 reservation_id BIGINT NOT NULL UNIQUE,
 user_id BIGINT NOT NULL,
 rating INTEGER NOT NULL CHECK(rating BETWEEN 1 AND 5),
 content VARCHAR(2000) NOT NULL CHECK(char_length(trim(content)) BETWEEN 1 AND 2000),
 version BIGINT NOT NULL DEFAULT 1 CHECK(version>0),
 created_at TIMESTAMP NOT NULL,
 updated_at TIMESTAMP NOT NULL CHECK(updated_at>=created_at),
 FOREIGN KEY(reservation_id,user_id) REFERENCES reservations(id,user_id),
 UNIQUE(id,user_id,reservation_id)
);
CREATE TABLE review_operations (
 operation_id UUID PRIMARY KEY,
 review_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 reservation_id BIGINT NOT NULL,
 action VARCHAR(10) NOT NULL CHECK(action IN('create','update')),
 request JSONB NOT NULL CHECK(jsonb_typeof(request)='object'),
 receipt JSONB NOT NULL CHECK(jsonb_typeof(receipt)='object'),
 occurred_at TIMESTAMP NOT NULL,
 FOREIGN KEY(review_id,actor_id,reservation_id) REFERENCES reviews(id,user_id,reservation_id)
);
CREATE FUNCTION preserve_review_operation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'review operation receipts are immutable' USING ERRCODE='23514'; END $$;
CREATE TRIGGER preserve_review_operation BEFORE UPDATE ON review_operations FOR EACH ROW EXECUTE FUNCTION preserve_review_operation();
