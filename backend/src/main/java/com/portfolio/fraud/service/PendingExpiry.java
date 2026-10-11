package com.portfolio.fraud.service;
import com.portfolio.fraud.repository.TransactionRepository;
import com.portfolio.fraud.entity.*;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component @EnableScheduling
public class PendingExpiry {
    private final TransactionRepository transactions;private final JdbcTemplate db;private final LedgerJournal journal;private final TransactionTemplate unit;
    public PendingExpiry(TransactionRepository t,JdbcTemplate db,LedgerJournal j,PlatformTransactionManager manager){transactions=t;this.db=db;journal=j;unit=new TransactionTemplate(manager);}
    @Scheduled(fixedDelay=5000)
    public void expire() {
        var ids=db.queryForList("SELECT id FROM transactions WHERE (status='PENDING_CONFIRMATION' AND confirmation_expires_at<=CURRENT_TIMESTAMP(6)) OR (status='AWAITING_REVIEW' AND review_expires_at<=CURRENT_TIMESTAMP(6)) ORDER BY id LIMIT 100",Long.class);
        for(Long id:ids)unit.executeWithoutResult(s->{var tx=transactions.findByIdForUpdate(id).orElseThrow();if(tx.expired(Instant.now())){String previous=tx.getStatus().name();tx.resolve(TransactionStatus.EXPIRED,"Authorization period expired. No funds moved.",Instant.now());journal.changed(null,"EXPIRED",tx,previous,tx.getOutcomeReason());}});
    }
}
