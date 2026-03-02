-- Add dead letter queue table for permanently failed sagas

CREATE TABLE IF NOT EXISTS dead_letter_saga (
    id BIGSERIAL PRIMARY KEY,
    saga_instance_id BIGINT NOT NULL,
    saga_type VARCHAR(100) NOT NULL,
    last_status VARCHAR(50) NOT NULL,
    context_snapshot TEXT,
    error_details TEXT,
    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dlq_saga_instance ON dead_letter_saga(saga_instance_id);
CREATE INDEX IF NOT EXISTS idx_dlq_saga_type ON dead_letter_saga(saga_type);
