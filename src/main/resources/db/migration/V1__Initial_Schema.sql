-- Database schema initialization for Wallet application with PostgreSQL

-- Create users table (sharded)
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255),
    email VARCHAR(255)
);

-- Create wallet table (sharded by user_id)
-- BASELINE: This represents the original wallet structure
CREATE TABLE IF NOT EXISTS wallet (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL,
    balance DECIMAL(19,2) NOT NULL DEFAULT 0.00
);

-- Create saga_instance table (sharded by id)
CREATE TABLE IF NOT EXISTS saga_instance (
    id BIGSERIAL PRIMARY KEY,
    saga_type VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    context JSONB NOT NULL,
    current_step VARCHAR(255) NOT NULL,
    completed_date TIMESTAMP,
    compensated_date TIMESTAMP,
    error_details TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    timeout_minutes INTEGER NOT NULL DEFAULT 60,
    expiry_time TIMESTAMP,
    version BIGINT,
    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create saga_step table (sharded by saga_instance_id)
CREATE TABLE IF NOT EXISTS saga_step (
    id BIGSERIAL PRIMARY KEY,
    saga_instance_id BIGINT NOT NULL,
    step_order INTEGER NOT NULL,
    step_name VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    compensation_action VARCHAR(100),
    error_message TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    step_data JSONB,
    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_date TIMESTAMP,
    completed_date TIMESTAMP,
    version BIGINT,
    CONSTRAINT fk_saga_step_instance FOREIGN KEY (saga_instance_id) REFERENCES saga_instance(id)
);

-- Create transactions table (sharded by id)
CREATE TABLE IF NOT EXISTS transactions (
    id BIGSERIAL PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    source_wallet_id BIGINT NOT NULL,
    destination_wallet_id BIGINT NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    status VARCHAR(50) NOT NULL,
    type VARCHAR(50) NOT NULL,
    saga_instance_id BIGINT NOT NULL,
    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for saga_instance
CREATE INDEX IF NOT EXISTS idx_saga_status_created ON saga_instance(status, created_date);
CREATE INDEX IF NOT EXISTS idx_saga_type_status ON saga_instance(saga_type, status);

-- Create indexes for saga_step
CREATE INDEX IF NOT EXISTS idx_step_saga_order ON saga_step(saga_instance_id, step_order);
CREATE INDEX IF NOT EXISTS idx_step_saga_status ON saga_step(saga_instance_id, status);

-- Create unique constraint for saga_step
CREATE UNIQUE INDEX IF NOT EXISTS uk_saga_step_order ON saga_step(saga_instance_id, step_order);
