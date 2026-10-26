package com.portfolio.fraud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.exception.ApiException;
import com.portfolio.fraud.repository.AccountRepository;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.portfolio.fraud.service.Recurrence.Frequency;

@Service
public class RecurringPaymentService {
    public record Request(@NotNull @Positive Long senderAccountId,@NotNull @Positive Long recipientAccountId,
        @NotNull @DecimalMin("0.01") @Digits(integer=17,fraction=2) BigDecimal amount,
        @Size(max=255) String description,@NotNull Instant firstExecuteAt,@NotNull Frequency frequency,
        @NotBlank @Size(max=80) String timeZone,@Min(1) @Max(120) int occurrenceCount) {}
    public record Plan(Long id,Long senderAccountId,Long recipientAccountId,BigDecimal amount,String currency,
        String description,Instant firstExecuteAt,String frequency,String timeZone,int occurrenceCount,
        int generatedCount,Instant nextUnqueuedAt,String status,Instant createdAt) {}
    private final JdbcTemplate db;private final AccountRepository accounts;private final ScheduledPaymentService schedules;
    private final ObjectMapper json;private final Clock clock;private final LedgerJournal journal;
    public RecurringPaymentService(JdbcTemplate db,AccountRepository accounts,ScheduledPaymentService schedules,ObjectMapper json,Clock clock,LedgerJournal journal){
        this.db=db;this.accounts=accounts;this.schedules=schedules;this.json=json;this.clock=clock;this.journal=journal;
    }
    @Transactional
    public Plan create(User user,String key,Request request) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,80}"))throw bad("Provide an Idempotency-Key of 16–80 letters, digits, hyphens or underscores.");
        String encoded=encode(request);
        db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user.getId());
        var previous=db.queryForList("SELECT id,request_json FROM recurring_payment_plans WHERE user_id=? AND request_key=?",user.getId(),key);
        if(!previous.isEmpty()){
            var row=previous.getFirst();
            if(!encoded.equals(row.get("request_json")))throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","This key was used for a different recurring plan.");
            return detail(user,((Number)row.get("id")).longValue());
        }
        if(request.firstExecuteAt()==null||!request.firstExecuteAt().isAfter(clock.instant()))throw bad("Choose a first execution time in the future.");
        if(request.occurrenceCount()<1||request.occurrenceCount()>120||request.frequency()==null)throw bad("Choose a recurrence and between 1 and 120 occurrences.");
        if(request.amount()==null||request.amount().signum()<=0||request.amount().scale()>2||request.amount().precision()-request.amount().scale()>17)throw bad("Enter a positive amount with at most two decimal places.");
        ZoneId zone;
        try{zone=ZoneId.of(request.timeZone());}catch(Exception e){throw bad("Choose a valid IANA time zone.");}
        // Validate the entire finite range before inserting a plan.
        try{
            if(Recurrence.at(request.firstExecuteAt(),zone,request.frequency(),request.occurrenceCount()-1).isAfter(Instant.parse("2037-12-31T23:59:59Z")))
                throw bad("All occurrences must be before 2038, within the supported MySQL timestamp range.");
        }catch(DateTimeException e){throw bad("The final occurrence is outside the supported date range.");}
        if(request.senderAccountId().equals(request.recipientAccountId()))throw bad("Choose a different recipient account.");
        var sender=accounts.findById(request.senderAccountId()).orElseThrow(()->bad("Source account does not exist."));
        var recipient=accounts.findById(request.recipientAccountId()).orElseThrow(()->bad("Recipient account does not exist."));
        if(!sender.getUser().getId().equals(user.getId()))throw new ApiException(HttpStatus.FORBIDDEN,"ACCOUNT_FORBIDDEN","You cannot use this account.");
        if(sender.getStatus()!=AccountStatus.ACTIVE||recipient.getStatus()!=AccountStatus.ACTIVE)throw bad("Both accounts must be active.");
        if(!sender.getCurrency().equals(recipient.getCurrency()))throw bad("Transfers require accounts in the same currency.");
        db.update("INSERT INTO recurring_payment_plans(user_id,sender_account_id,recipient_account_id,amount,description,frequency,time_zone,first_execute_at,next_execute_at,occurrence_count,request_key,request_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            user.getId(),sender.getId(),recipient.getId(),request.amount(),request.description(),request.frequency().name(),zone.getId(),Timestamp.from(request.firstExecuteAt()),Timestamp.from(request.firstExecuteAt()),request.occurrenceCount(),key,encoded);
        Long id=db.queryForObject("SELECT id FROM recurring_payment_plans WHERE user_id=? AND request_key=?",Long.class,user.getId(),key);
        journal.audit(user.getId(),"RECURRING_PLAN_CREATED",null,null,"Recurring plan #"+id+" authorized for "+request.occurrenceCount()+" occurrences. No funds reserved.");
        // The new row is already exclusively owned by this transaction.
        generate(id);return detail(user,id);
    }
    @Transactional(readOnly=true)
    public List<Plan> list(User user,int page,int size){page(page,size);return db.queryForList("SELECT id FROM recurring_payment_plans WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",Long.class,user.getId(),size,(long)page*size).stream().map(id->detail(user,id)).toList();}
    @Transactional(readOnly=true)
    public Plan detail(User user,Long id) {
        var rows=db.queryForList("SELECT p.*,a.currency FROM recurring_payment_plans p JOIN accounts a ON a.id=p.sender_account_id WHERE p.id=? AND p.user_id=?",id,user.getId());
        if(rows.isEmpty())throw missing();var r=rows.getFirst();
        return new Plan(id,number(r,"sender_account_id"),number(r,"recipient_account_id"),(BigDecimal)r.get("amount"),(String)r.get("currency"),(String)r.get("description"),instant(r.get("first_execute_at")),(String)r.get("frequency"),(String)r.get("time_zone"),((Number)r.get("occurrence_count")).intValue(),((Number)r.get("next_index")).intValue(),instant(r.get("next_execute_at")),(String)r.get("status"),instant(r.get("created_at")));
    }
    @Transactional(readOnly=true)
    public List<ScheduledPaymentService.Payment> occurrences(User user,Long id,int page,int size){
        detail(user,id);page(page,size);
        return db.queryForList("SELECT id FROM scheduled_payments WHERE recurring_plan_id=? ORDER BY occurrence_index LIMIT ? OFFSET ?",Long.class,id,size,(long)page*size).stream().map(schedule->schedules.detail(user,schedule)).toList();
    }
    @Transactional
    public Plan cancel(User user,Long id) {
        var rows=db.queryForList("SELECT status FROM recurring_payment_plans WHERE id=? AND user_id=? FOR UPDATE",id,user.getId());
        if(rows.isEmpty())throw missing();
        if("ACTIVE".equals(rows.getFirst().get("status"))){
            db.update("UPDATE recurring_payment_plans SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP(6),next_execute_at=NULL WHERE id=?",id);
            // Same schedule lock as execution: processed transactions keep their own lifecycle.
            db.update("UPDATE scheduled_payments SET status='CANCELLED',resolved_at=CURRENT_TIMESTAMP(6),outcome_reason='Recurring plan cancelled before execution. No funds moved.' WHERE recurring_plan_id=? AND status='SCHEDULED'",id);
            journal.audit(user.getId(),"RECURRING_PLAN_CANCELLED",null,"ACTIVE","Recurring plan #"+id+" cancelled. Existing processed transactions retain their recorded outcomes.");
        }
        return detail(user,id);
    }
    /** Persistent 45-day horizon; row lock + unique(plan,index) make reruns and concurrent workers safe. */
    @Transactional
    public void generate(Long id) {
        var rows=db.queryForList("SELECT * FROM recurring_payment_plans WHERE id=? AND status='ACTIVE' FOR UPDATE",id);
        if(rows.isEmpty())return;var r=rows.getFirst();
        int index=((Number)r.get("next_index")).intValue(),count=((Number)r.get("occurrence_count")).intValue();
        Instant first=instant(r.get("first_execute_at")),horizon=clock.instant().plus(Duration.ofDays(45));
        ZoneId zone=ZoneId.of((String)r.get("time_zone"));var frequency=Frequency.valueOf((String)r.get("frequency"));
        int batch=0;
        while(index<count&&batch<100){
            Instant at=Recurrence.at(first,zone,frequency,index);if(at.isAfter(horizon))break;
            var input=new ScheduledPaymentService.Request(number(r,"sender_account_id"),number(r,"recipient_account_id"),(BigDecimal)r.get("amount"),(String)r.get("description"),at);
            schedules.materialize(number(r,"user_id"),id,index,input);index++;batch++;
        }
        Instant next=index<count?Recurrence.at(first,zone,frequency,index):null;
        db.update("UPDATE recurring_payment_plans SET next_index=?,next_execute_at=? WHERE id=?",index,next==null?null:Timestamp.from(next),id);
    }
    private String encode(Object input){try{return json.writeValueAsString(input);}catch(Exception e){throw new IllegalStateException("Unable to encode recurring plan",e);}}
    private long number(Map<String,Object> r,String key){return ((Number)r.get(key)).longValue();}
    private Instant instant(Object v){return v==null?null:v instanceof Timestamp t?t.toInstant():((LocalDateTime)v).toInstant(ZoneOffset.UTC);}
    private void page(int page,int size){if(page<0||size<1||size>100)throw bad("Page must be nonnegative and size between 1 and 100.");}
    private ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_RECURRENCE",message);}
    private ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"RECURRING_PLAN_NOT_FOUND","Recurring plan not found.");}
}
