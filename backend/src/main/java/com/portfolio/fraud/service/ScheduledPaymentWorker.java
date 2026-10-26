package com.portfolio.fraud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledPaymentWorker {
    private static final Logger log=LoggerFactory.getLogger(ScheduledPaymentWorker.class);
    private final JdbcTemplate db;
    private final ScheduledPaymentService payments;
    public ScheduledPaymentWorker(JdbcTemplate db,ScheduledPaymentService payments){this.db=db;this.payments=payments;}
    @Scheduled(fixedDelayString="${app.scheduled-payments.poll-ms:5000}",initialDelayString="${app.scheduled-payments.initial-delay-ms:0}")
    public void processDue() {
        var ids=db.queryForList("SELECT id FROM scheduled_payments WHERE status='SCHEDULED' AND execute_at<=CURRENT_TIMESTAMP(6) ORDER BY execute_at,id LIMIT 100",Long.class);
        for(Long id:ids)try{payments.execute(id);}catch(Exception e){log.error("Scheduled payment {} failed; rolled back and will retry.",id,e);}
    }
}
