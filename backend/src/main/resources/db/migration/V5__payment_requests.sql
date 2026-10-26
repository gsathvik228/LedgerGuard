-- V4 is reserved for the scheduled-payments feature already merged upstream.
ALTER TABLE notifications MODIFY transaction_id BIGINT NULL,
 ADD COLUMN resource_type VARCHAR(32) NULL,
 ADD COLUMN resource_id BIGINT NULL,
 ADD UNIQUE KEY uk_notification_resource(user_id,resource_type,resource_id,event_key);

CREATE TABLE payment_requests (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 requester_id BIGINT NOT NULL, recipient_id BIGINT NOT NULL, receiving_account_id BIGINT NOT NULL,
 amount DECIMAL(19,2) NOT NULL, currency CHAR(3) NOT NULL, description VARCHAR(255) NOT NULL,
 status VARCHAR(32) NOT NULL, transaction_id BIGINT NULL,
 expires_at TIMESTAMP(6) NULL, resolved_at TIMESTAMP(6) NULL,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 INDEX ix_request_sent(requester_id,created_at,id), INDEX ix_request_received(recipient_id,created_at,id),
 INDEX ix_request_expiry(status,expires_at), UNIQUE KEY uk_request_transaction(transaction_id),
 FOREIGN KEY(requester_id) REFERENCES users(id), FOREIGN KEY(recipient_id) REFERENCES users(id),
 FOREIGN KEY(receiving_account_id) REFERENCES accounts(id), FOREIGN KEY(transaction_id) REFERENCES transactions(id),
 CONSTRAINT ck_request_amount CHECK(amount>0), CONSTRAINT ck_request_participants CHECK(requester_id<>recipient_id),
 CONSTRAINT ck_request_status CHECK(status IN ('PENDING','PAYMENT_PENDING','PAID','DECLINED','CANCELLED','EXPIRED'))
);
CREATE TABLE payment_request_attempts (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, request_id BIGINT NOT NULL, transaction_id BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_request_attempt(transaction_id), INDEX ix_request_attempts(request_id,id),
 FOREIGN KEY(request_id) REFERENCES payment_requests(id), FOREIGN KEY(transaction_id) REFERENCES transactions(id)
);
