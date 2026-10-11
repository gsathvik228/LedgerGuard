package com.portfolio.fraud.service;

import com.portfolio.fraud.dto.TransactionDtos.TransactionResponse;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.exception.ApiException;
import com.portfolio.fraud.fraud.FraudDetectionService;
import com.portfolio.fraud.repository.*;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ReviewService {
    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final FraudAnalysisRepository analyses;
    private final FraudDetectionService fraud;
    private final TransactionService views;
    private final LedgerJournal journal;
    public ReviewService(TransactionRepository t,AccountRepository a,FraudAnalysisRepository fa,FraudDetectionService f,TransactionService v,LedgerJournal j){transactions=t;accounts=a;analyses=fa;fraud=f;views=v;journal=j;}
    @Transactional(isolation=Isolation.READ_COMMITTED,noRollbackFor=ApiException.class)
    public TransactionResponse decide(User admin,Long id,boolean approve,String reason) {
        if(admin.getRole()!=Role.ADMIN)throw new ApiException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","Administrator access is required.");
        if(reason==null || reason.isBlank() || reason.length()>180)throw new ApiException(HttpStatus.BAD_REQUEST,"REASON_REQUIRED","Provide a decision reason of 1–180 characters.");
        Transaction tx=transactions.findByIdForUpdate(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Transaction not found."));
        if(tx.getStatus()!=TransactionStatus.AWAITING_REVIEW)throw new ApiException(HttpStatus.CONFLICT,"REVIEW_CLOSED","This request is no longer awaiting review. Reload its recorded outcome.");
        if(tx.expired(Instant.now()))return expire(tx,admin);
        if(!approve) {
            tx.resolve(TransactionStatus.REJECTED,"Administrator rejected the request. No funds moved. "+reason.strip(),Instant.now());
            journal.changed(admin.getId(),"ADMIN_REJECTED",tx,"AWAITING_REVIEW",tx.getOutcomeReason());
            return views.response(tx,admin);
        }
        Long senderId=tx.getSenderAccount().getId(),recipientId=tx.getRecipientAccount()==null?null:tx.getRecipientAccount().getId();
        Account sender,recipient=null;
        if(recipientId==null)sender=lock(senderId);
        else {Account low=lock(Math.min(senderId,recipientId)),high=lock(Math.max(senderId,recipientId));sender=senderId.equals(low.getId())?low:high;recipient=recipientId.equals(low.getId())?low:high;}
        if(tx.expired(Instant.now()))return expire(tx,admin);
        String invalid=null;
        if(!sender.getUser().getId().equals(tx.getInitiatedBy().getId()))invalid="Account ownership changed.";
        else if(sender.getStatus()!=AccountStatus.ACTIVE || recipient!=null && recipient.getStatus()!=AccountStatus.ACTIVE)invalid="An account is inactive.";
        else if(recipient!=null && (senderId.equals(recipientId)||!sender.getCurrency().equals(recipient.getCurrency())))invalid="Transfer accounts are incompatible.";
        else if(sender.getBalance().compareTo(tx.getAmount())<0)invalid="Insufficient funds at approval.";
        var risk=fraud.evaluate(tx.getInitiatedBy(),sender,recipient,tx.getAmount(),id);
        tx.reassess(risk.score(),risk.level());
        FraudAnalysis assessment=new FraudAnalysis(tx,risk.score(),risk.level(),invalid==null?"ADMIN_OVERRIDE":"REJECT");assessment.review();
        risk.findings().forEach(f->assessment.add(new FraudRuleResult(f.rule(),f.reason(),f.severity(),f.points())));analyses.save(assessment);
        if(invalid!=null)tx.resolve(TransactionStatus.FAILED,invalid+" No funds moved.",Instant.now());
        else {sender.debit(tx.getAmount());if(recipient!=null)recipient.credit(tx.getAmount());tx.resolve(TransactionStatus.COMPLETED,"Completed with an explicit administrator override of the fraud decision.",Instant.now());}
        journal.audit(admin.getId(),"ADMIN_APPROVAL_DECISION",tx,"AWAITING_REVIEW","Explicit fraud override requested: "+reason.strip());
        journal.changed(admin.getId(),invalid==null?"ADMIN_APPROVED":"ADMIN_APPROVAL_FAILED",tx,"AWAITING_REVIEW",tx.getOutcomeReason());
        return views.response(tx,admin);
    }
    private TransactionResponse expire(Transaction tx,User admin){tx.resolve(TransactionStatus.EXPIRED,"Admin review period expired. No funds moved.",Instant.now());journal.changed(null,"EXPIRED",tx,"AWAITING_REVIEW",tx.getOutcomeReason());return views.response(tx,admin);}
    private Account lock(Long id){return accounts.findByIdForUpdate(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Account not found."));}
}
