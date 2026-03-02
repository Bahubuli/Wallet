-- Add idempotency key table for saga step deduplication

CREATE TABLE IF NOT EXISTS saga_idempotency_key (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    saga_instance_id BIGINT NOT NULL,
    step_name VARCHAR(100) NOT NULL,
    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_idempotency_key ON saga_idempotency_key(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_idempotency_saga_instance ON saga_idempotency_key(saga_instance_id);
