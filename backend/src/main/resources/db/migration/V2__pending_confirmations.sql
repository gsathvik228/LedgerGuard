ALTER TABLE transactions
    MODIFY COLUMN status VARCHAR(32) NOT NULL,
    ADD COLUMN confirmation_expires_at TIMESTAMP(6) NULL,
    ADD COLUMN resolved_at TIMESTAMP(6) NULL,
    ADD COLUMN outcome_reason VARCHAR(255) NULL,
    ADD INDEX ix_pending_expiry (status, confirmation_expires_at);

-- Retain every existing assessment as ORIGINAL. A separate assessment is
-- appended only when a password-authorized confirmation is reassessed.
ALTER TABLE fraud_analyses
    ADD COLUMN phase VARCHAR(20) NOT NULL DEFAULT 'ORIGINAL',
    ADD UNIQUE KEY uk_analysis_transaction_phase (transaction_id, phase);
ALTER TABLE fraud_analyses DROP INDEX transaction_id;

CREATE TABLE confirmation_guards (
    user_id BIGINT PRIMARY KEY,
    failed_attempts INT NOT NULL DEFAULT 0,
    window_started_at TIMESTAMP(6) NOT NULL,
    locked_until TIMESTAMP(6) NULL,
    CONSTRAINT fk_confirmation_guard_user FOREIGN KEY (user_id) REFERENCES users(id)
);

