CREATE TABLE recurring_payment_plans (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
 sender_account_id BIGINT NOT NULL, recipient_account_id BIGINT NOT NULL,
 amount DECIMAL(19,2) NOT NULL, description VARCHAR(255) NULL,
 frequency VARCHAR(16) NOT NULL, time_zone VARCHAR(80) NOT NULL,
 first_execute_at TIMESTAMP(6) NOT NULL, next_execute_at TIMESTAMP(6) NULL,
 occurrence_count INT NOT NULL, next_index INT NOT NULL DEFAULT 0,
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 request_key VARCHAR(80) NOT NULL, request_json TEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), cancelled_at TIMESTAMP(6) NULL,
 UNIQUE KEY uk_recurring_request(user_id,request_key), INDEX ix_recurring_due(status,next_execute_at,id),
 INDEX ix_recurring_owner(user_id,id),
 FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(sender_account_id) REFERENCES accounts(id),
 FOREIGN KEY(recipient_account_id) REFERENCES accounts(id),
 CONSTRAINT ck_recurring_amount CHECK(amount>0),
 CONSTRAINT ck_recurring_count CHECK(occurrence_count BETWEEN 1 AND 120),
 CONSTRAINT ck_recurring_frequency CHECK(frequency IN ('DAILY','WEEKLY','MONTHLY','YEARLY')),
 CONSTRAINT ck_recurring_status CHECK(status IN ('ACTIVE','CANCELLED'))
);
ALTER TABLE scheduled_payments ADD COLUMN recurring_plan_id BIGINT NULL,
 ADD COLUMN occurrence_index INT NULL,
 ADD CONSTRAINT fk_schedule_recurring FOREIGN KEY(recurring_plan_id) REFERENCES recurring_payment_plans(id),
 ADD UNIQUE KEY uk_recurring_occurrence(recurring_plan_id,occurrence_index);
