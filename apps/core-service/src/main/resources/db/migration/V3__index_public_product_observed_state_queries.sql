-- 현재 baseline 규모에서는 일반 CREATE INDEX의 짧은 write lock을 허용합니다.
-- 운영 데이터가 쌓인 테이블의 후속 index migration은 CREATE INDEX CONCURRENTLY와
-- flyway:executeInTransaction=false를 함께 사용하고 별도 운영 검증을 거쳐야 합니다.

CREATE INDEX public_observation_product_time_idx
    ON public_observation (product_key, observed_at DESC, observation_id DESC);

CREATE INDEX extraction_attempt_observation_time_idx
    ON extraction_attempt (observation_id, attempted_at DESC, extraction_attempt_id DESC);

CREATE INDEX extraction_attempt_product_success_time_idx
    ON extraction_attempt (product_key, attempted_at DESC, extraction_attempt_id DESC)
    WHERE status = 'SUCCEEDED';

CREATE INDEX collection_attempt_product_time_idx
    ON collection_attempt (product_key, attempted_at DESC, collection_attempt_id DESC);
