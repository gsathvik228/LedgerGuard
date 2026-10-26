package com.portfolio.fraud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.fraud.dto.TransactionDtos.*;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.exception.ApiException;
import com.portfolio.fraud.repository.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduledPaymentService {
    public record Request(@NotNull Long senderAccountId,@NotNull Long recipientAccountId,
        @NotNull @DecimalMin("0.01") @Digits(integer=17,fraction=2) BigDecimal amount,
        @Size(max=255) String description,@NotNull Instant executeAt) {}
    public record Payment(Long id,Long senderAccountId,Long recipientAccountId,BigDecimal amount,
        String currency,String description,Instant executeAt,String status,String outcomeReason,
        Instant createdAt,TransactionResponse transaction) {}
    private final JdbcTemplate db;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final TransactionService transfers;
    private final ConfirmationService confirmations;
    private final LedgerJournal journal;
    private final ObjectMapper json;
    public ScheduledPaymentService(JdbcTemplate db,AccountRepository accounts,UserRepository users,
        TransactionService transfers,ConfirmationService confirmations,LedgerJournal journal,ObjectMapper json) {
        this.db=db;this.accounts=accounts;this.users=users;this.transfers=transfers;
        this.confirmations=confirmations;this.journal=journal;this.json=json;
    }
    private ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_SCHEDULE",message);}
    private ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"SCHEDULE_NOT_FOUND","Scheduled payment not found.");}
    @Transactional
    public Payment create(User user,String key,Request request) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,80}"))throw bad("Provide an Idempotency-Key of 16–80 letters, digits, hyphens or underscores.");
        String encoded;
        try{encoded=json.writeValueAsString(request);}catch(Exception e){throw new IllegalStateException(e);}
        // Serialize creation for this owner, including concurrent retries. Never retain credentials.
        db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user.getId());
        var existing=db.queryForList("SELECT id,request_json FROM scheduled_payments WHERE user_id=? AND request_key=?",user.getId(),key);
        if(!existing.isEmpty()){
            var row=existing.getFirst();
            if(!encoded.equals(row.get("request_json")))throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","This key was used for a different schedule.");
            return detail(user,((Number)row.get("id")).longValue());
        }
        if(!request.executeAt().isAfter(Instant.now()))throw bad("Choose an execution time in the future.");
        if(request.senderAccountId().equals(request.recipientAccountId()))throw bad("Choose a different recipient account.");
        Account sender=accounts.findById(request.senderAccountId()).orElseThrow(()->bad("Source account does not exist."));
        Account recipient=accounts.findById(request.recipientAccountId()).orElseThrow(()->bad("Recipient account does not exist."));
        if(!sender.getUser().getId().equals(user.getId()))throw new ApiException(HttpStatus.FORBIDDEN,"ACCOUNT_FORBIDDEN","You cannot use this account.");
        if(sender.getStatus()!=AccountStatus.ACTIVE||recipient.getStatus()!=AccountStatus.ACTIVE)throw bad("Both accounts must be active.");
        if(!sender.getCurrency().equals(recipient.getCurrency()))throw bad("Transfers require accounts in the same currency.");
        db.update("INSERT INTO scheduled_payments(user_id,sender_account_id,recipient_account_id,amount,description,execute_at,request_key,request_json) VALUES(?,?,?,?,?,?,?,?)",
            user.getId(),sender.getId(),recipient.getId(),request.amount(),request.description(),Timestamp.from(request.executeAt()),key,encoded);
        Long id=db.queryForObject("SELECT id FROM scheduled_payments WHERE user_id=? AND request_key=?",Long.class,user.getId(),key);
        journal.audit(user.getId(),"SCHEDULE_CREATED",null,null,"Scheduled payment #"+id+" authorized. No funds moved or reserved.");
        return detail(user,id);
    }
    @Transactional(readOnly=true)
    public List<Payment> list(User user,int page,int size) {
        if(page<0||size<1||size>100)throw bad("Page must be nonnegative and size between 1 and 100.");
        return db.queryForList("SELECT id FROM scheduled_payments WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",Long.class,user.getId(),size,(long)page*size)
            .stream().map(id->detail(user,id)).toList();
    }
    @Transactional(readOnly=true)
    public Payment detail(User user,Long id) {
        var rows=db.queryForList("SELECT s.*,a.currency FROM scheduled_payments s JOIN accounts a ON a.id=s.sender_account_id WHERE s.id=? AND s.user_id=?",id,user.getId());
        if(rows.isEmpty())throw missing();
        var r=rows.getFirst();
        Long tx=r.get("transaction_id")==null?null:((Number)r.get("transaction_id")).longValue();
        return new Payment(id,((Number)r.get("sender_account_id")).longValue(),((Number)r.get("recipient_account_id")).longValue(),
            (BigDecimal)r.get("amount"),r.get("currency").toString(),(String)r.get("description"),
            instant(r.get("execute_at")),r.get("status").toString(),(String)r.get("outcome_reason"),instant(r.get("created_at")),tx==null?null:transfers.detail(user,tx));
    }
    private Instant instant(Object value){return value instanceof Timestamp t?t.toInstant():((java.time.LocalDateTime)value).toInstant(java.time.ZoneOffset.UTC);}
    @Transactional
    public Payment cancel(User user,Long id) {
        var rows=db.queryForList("SELECT status,transaction_id FROM scheduled_payments WHERE id=? AND user_id=? FOR UPDATE",id,user.getId());
        if(rows.isEmpty())throw missing();
        var row=rows.getFirst();
        if("SCHEDULED".equals(row.get("status"))){
            db.update("UPDATE scheduled_payments SET status='CANCELLED',resolved_at=CURRENT_TIMESTAMP(6),outcome_reason='Cancelled before execution. No funds moved.' WHERE id=?",id);
            journal.audit(user.getId(),"SCHEDULE_CANCELLED",null,"SCHEDULED","Scheduled payment #"+id+" cancelled. No funds moved.");
        } else if(row.get("transaction_id")!=null) {
            var tx=transfers.detail(user,((Number)row.get("transaction_id")).longValue());
            if(tx.canCancel())confirmations.cancel(user,tx.id());
        }
        return detail(user,id);
    }
    // Called through the Spring proxy: one schedule, transfer and outcome commit together.
    @Transactional
    public void execute(Long id) {
        var rows=db.queryForList("SELECT * FROM scheduled_payments WHERE id=? AND status='SCHEDULED' AND execute_at<=CURRENT_TIMESTAMP(6) FOR UPDATE",id);
        if(rows.isEmpty())return;
        var r=rows.getFirst();
        User user=users.findById(((Number)r.get("user_id")).longValue()).orElseThrow();
        TransactionResponse tx;
        try {
            tx=transfers.transfer(user,new TransferRequest(((Number)r.get("sender_account_id")).longValue(),
                ((Number)r.get("recipient_account_id")).longValue(),(BigDecimal)r.get("amount"),(String)r.get("description")));
        } catch(ApiException e) {
            db.update("UPDATE scheduled_payments SET status='FAILED',outcome_reason=?,resolved_at=CURRENT_TIMESTAMP(6) WHERE id=?",e.getMessage(),id);
            journal.audit(null,"SCHEDULE_FAILED",null,"SCHEDULED","Scheduled payment #"+id+": "+e.getMessage());
            return;
        }
        db.update("UPDATE scheduled_payments SET status='PROCESSED',transaction_id=?,resolved_at=CURRENT_TIMESTAMP(6) WHERE id=?",tx.id(),id);
        journal.audit(null,"SCHEDULE_PROCESSED",null,"SCHEDULED","Scheduled payment #"+id+" produced transaction #"+tx.id()+".");
    }
}
