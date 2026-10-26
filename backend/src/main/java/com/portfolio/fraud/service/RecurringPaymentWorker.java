package com.portfolio.fraud.service;

import java.sql.Timestamp;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;

/**
 * Discovers active plans within the rolling generation horizon. Each plan is queued
 * in its own service transaction; failed generation rolls back and can be rediscovered.
 * The scheduled-payment worker, not this generator, executes the resulting transfers.
 */
@Component
public class RecurringPaymentWorker {
    private static final Logger log=LoggerFactory.getLogger(RecurringPaymentWorker.class);
    private final JdbcTemplate db;private final RecurringPaymentService plans;private final Clock clock;
    public RecurringPaymentWorker(JdbcTemplate db,RecurringPaymentService plans,Clock clock){this.db=db;this.plans=plans;this.clock=clock;}
    @Scheduled(fixedDelayString="${app.recurring-payments.poll-ms:30000}",initialDelayString="${app.recurring-payments.initial-delay-ms:0}")
    public void process(){
        var ids=db.queryForList("SELECT id FROM recurring_payment_plans WHERE status='ACTIVE' AND next_execute_at<=? ORDER BY next_execute_at,id LIMIT 100",Long.class,Timestamp.from(clock.instant().plus(Duration.ofDays(45))));
        for(Long id:ids)try{plans.generate(id);}catch(Exception e){log.error("Recurring plan {} generation rolled back; will retry.",id,e);}
    }
}
