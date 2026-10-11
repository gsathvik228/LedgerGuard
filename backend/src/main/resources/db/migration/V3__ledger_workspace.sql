CREATE TABLE idempotency_records (
 user_id BIGINT NOT NULL, operation VARCHAR(80) NOT NULL, request_key VARCHAR(80) NOT NULL,
 fingerprint CHAR(64) NOT NULL, response_json LONGTEXT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(user_id, operation, request_key), FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE audit_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, actor_id BIGINT NULL, action VARCHAR(60) NOT NULL,
 transaction_id BIGINT NULL, previous_status VARCHAR(32) NULL, new_status VARCHAR(32) NULL,
 reason VARCHAR(500) NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_audit_tx(transaction_id,id), INDEX ix_audit_actor(actor_id,id),
 FOREIGN KEY(transaction_id) REFERENCES transactions(id), FOREIGN KEY(actor_id) REFERENCES users(id)
);
CREATE TABLE notifications (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, transaction_id BIGINT NOT NULL,
 event_key VARCHAR(80) NOT NULL, message VARCHAR(500) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), read_at TIMESTAMP(6) NULL,
 UNIQUE KEY uk_notification(user_id,transaction_id,event_key), INDEX ix_notification_user(user_id,id),
 FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(transaction_id) REFERENCES transactions(id)
);
CREATE TABLE beneficiaries (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
 friendly_name VARCHAR(80) NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_beneficiary(user_id,account_id), FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(account_id) REFERENCES accounts(id)
);
CREATE TABLE user_sessions (
 id CHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 last_activity_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NOT NULL, revoked_at TIMESTAMP(6) NULL,
 user_agent VARCHAR(255) NOT NULL, remote_address VARCHAR(80) NOT NULL,
 INDEX ix_session_user(user_id), FOREIGN KEY(user_id) REFERENCES users(id)
);
-- Legacy history cannot prove initial balances or historical settlement times.
-- Establish a verifiable starting point; never invent pre-migration balances.
CREATE TABLE account_baselines (
 account_id BIGINT PRIMARY KEY, balance DECIMAL(19,2) NOT NULL, recorded_at TIMESTAMP(6) NOT NULL,
 provenance VARCHAR(40) NOT NULL, FOREIGN KEY(account_id) REFERENCES accounts(id)
);
INSERT INTO account_baselines SELECT id,balance,CURRENT_TIMESTAMP(6),'MIGRATION_SNAPSHOT' FROM accounts;
CREATE TABLE ledger_movements (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id BIGINT NOT NULL, transaction_id BIGINT NOT NULL,
 amount DECIMAL(19,2) NOT NULL, occurred_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_movement(account_id,transaction_id), INDEX ix_movement_date(account_id,occurred_at),
 FOREIGN KEY(account_id) REFERENCES accounts(id), FOREIGN KEY(transaction_id) REFERENCES transactions(id)
);
ALTER TABLE transactions ADD COLUMN review_expires_at TIMESTAMP(6) NULL,
 ADD INDEX ix_review_expiry(status,review_expires_at);
