package com.portfolio.fraud.service;

import com.portfolio.fraud.entity.*;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Append-only event and movement writer. All writes join the business transaction. */
@Service @Transactional(propagation=Propagation.MANDATORY)
public class LedgerJournal {
    private final JdbcTemplate db;
    private final org.springframework.context.ApplicationEventPublisher publisher;
    public LedgerJournal(JdbcTemplate db,org.springframework.context.ApplicationEventPublisher publisher){this.db=db;this.publisher=publisher;}
    public void audit(Long actor,String action,Transaction tx,String previous,String reason) {
        db.update("INSERT INTO audit_events(actor_id,action,transaction_id,previous_status,new_status,reason) VALUES(?,?,?,?,?,?)",
            actor,action,tx==null?null:tx.getId(),previous,tx==null?null:tx.getStatus().name(),reason);
    }
    public void changed(Long actor,String action,Transaction tx,String previous,String reason) {
        audit(actor,action,tx,previous,reason);
        if(tx.getStatus()==TransactionStatus.COMPLETED) {
            Instant now=Instant.now();
            if(tx.getSenderAccount()!=null)movement(tx,tx.getSenderAccount(),tx.getAmount().negate(),now);
            if(tx.getRecipientAccount()!=null)movement(tx,tx.getRecipientAccount(),tx.getAmount(),now);
            if(tx.getType()==TransactionType.TRANSFER)notify(tx.getRecipientAccount().getUser().getId(),tx,"INCOMING","An incoming transfer has completed. Your account has been credited.");
        }
        if(tx.getStatus()!=TransactionStatus.COMPLETED || previous!=null)
            notify(tx.getInitiatedBy().getId(),tx,tx.getStatus().name(),reason);
        publisher.publishEvent(new TransactionStateChanged(tx.getId(),tx.getStatus()));
    }
    private void movement(Transaction tx,Account a,java.math.BigDecimal amount,Instant now) {
        db.update("INSERT INTO ledger_movements(account_id,transaction_id,amount,occurred_at) VALUES(?,?,?,?)",a.getId(),tx.getId(),amount,Timestamp.from(now));
    }
    public void baseline(Account account) {
        db.update("INSERT INTO account_baselines(account_id,balance,recorded_at,provenance) VALUES(?,?,?,?)",account.getId(),account.getBalance(),Timestamp.from(Instant.now()),"ACCOUNT_OPENING");
    }
    private void notify(Long user,Transaction tx,String event,String message) {
        db.update("INSERT INTO notifications(user_id,transaction_id,event_key,message) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE id=id",user,tx.getId(),event,message);
    }
}
