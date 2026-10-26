CREATE TABLE scheduled_payments (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL, sender_account_id BIGINT NOT NULL, recipient_account_id BIGINT NOT NULL,
 amount DECIMAL(19,2) NOT NULL, description VARCHAR(255) NULL, execute_at TIMESTAMP(6) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED', transaction_id BIGINT NULL,
 outcome_reason VARCHAR(500) NULL, request_key VARCHAR(80) NOT NULL, request_json TEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), resolved_at TIMESTAMP(6) NULL,
 UNIQUE KEY uk_schedule_request(user_id,request_key), UNIQUE KEY uk_schedule_transaction(transaction_id),
 INDEX ix_schedule_due(status,execute_at,id), INDEX ix_schedule_owner(user_id,id),
 FOREIGN KEY(user_id) REFERENCES users(id),
 FOREIGN KEY(sender_account_id) REFERENCES accounts(id), FOREIGN KEY(recipient_account_id) REFERENCES accounts(id),
 FOREIGN KEY(transaction_id) REFERENCES transactions(id)
);
