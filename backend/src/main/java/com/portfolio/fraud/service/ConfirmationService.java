package com.portfolio.fraud.service;

import com.portfolio.fraud.dto.TransactionDtos.TransactionResponse;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.exception.*;
import com.portfolio.fraud.fraud.FraudDetectionService;
import com.portfolio.fraud.repository.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfirmationService {
    private final LedgerJournal journal;
    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final ConfirmationGuardRepository guards;
    private final FraudAnalysisRepository analyses;
    private final FraudDetectionService fraud;
    private final PasswordEncoder encoder;
    private final TransactionService views;
    @Value("${app.confirmation.max-failures:5}") private int maximumFailures=5;
    @Value("${app.confirmation.attempt-window-seconds:300}") private long windowSeconds=300;
    @Value("${app.confirmation.lock-seconds:60}") private long lockSeconds=60;

    public ConfirmationService(TransactionRepository transactions,AccountRepository accounts,UserRepository users,
        ConfirmationGuardRepository guards,FraudAnalysisRepository analyses,FraudDetectionService fraud,
        PasswordEncoder encoder,TransactionService views,LedgerJournal journal) {
        this.journal=journal;this.transactions=transactions;this.accounts=accounts;this.users=users;this.guards=guards;
        this.analyses=analyses;this.fraud=fraud;this.encoder=encoder;this.views=views;
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=ApiException.class)
    public TransactionResponse confirm(User actor,Long id,String password) {
        Transaction tx=owned(actor,id);
        Instant now=Instant.now();
        // The transaction row remains locked through the final commit. Retries
        // observe a terminal state and cannot debit the accounts a second time.
        if (tx.getStatus()!=TransactionStatus.PENDING_CONFIRMATION) return views.response(tx,actor);
        if (expire(tx,now)) return views.response(tx,actor);
        guards.ensureExists(actor.getId(),now);
        ConfirmationGuard guard=guards.lock(actor.getId());
        now=Instant.now();
        if (guard.isLocked(now)) {journal.audit(actor.getId(),"PASSWORD_RATE_LIMITED",tx,tx.getStatus().name(),"Too many failed password attempts.");throw new ConfirmationDenied(true);}
        User current=users.findById(actor.getId()).orElseThrow(()->new ApiException(HttpStatus.FORBIDDEN,"USER_NOT_FOUND","Account access is unavailable"));
        if (password==null || password.getBytes(StandardCharsets.UTF_8).length>72 || !encoder.matches(password,current.getPasswordHash())) {
            guard.failed(now,Math.max(1,maximumFailures),Math.max(1,windowSeconds),Math.max(1,lockSeconds));
            journal.audit(actor.getId(),"PASSWORD_REJECTED",tx,tx.getStatus().name(),"Current password was not accepted.");
            throw new ConfirmationDenied(guard.isLocked(now));
        }
        guard.succeeded(now);
        journal.audit(actor.getId(),"PASSWORD_VERIFIED",tx,tx.getStatus().name(),"Current password verified for this payment.");

        Long senderId=tx.getSenderAccount().getId();
        Long recipientId=tx.getRecipientAccount()==null?null:tx.getRecipientAccount().getId();
        Account sender,recipient=null;
        if (recipientId!=null) {
            Account low=locked(Math.min(senderId,recipientId));
            Account high=locked(Math.max(senderId,recipientId));
            sender=senderId.equals(low.getId())?low:high;
            recipient=recipientId.equals(low.getId())?low:high;
        } else sender=locked(senderId);

        // Recheck the deadline after waiting for guard/account locks and BCrypt.
        now=Instant.now();
        if (expire(tx,now)) return views.response(tx,actor);
        String invalid=null;
        if (!sender.getUser().getId().equals(actor.getId())) invalid="Account ownership changed. No funds moved.";
        else if (sender.getStatus()!=AccountStatus.ACTIVE || recipient!=null && recipient.getStatus()!=AccountStatus.ACTIVE)
            invalid="An account is no longer active. No funds moved.";
        else if (recipient!=null && (senderId.equals(recipientId) || !sender.getCurrency().equals(recipient.getCurrency())))
            invalid="The transfer accounts are no longer compatible. No funds moved.";
        else if (sender.getBalance().compareTo(tx.getAmount())<0)
            invalid="Insufficient funds at confirmation. No funds moved.";

        var risk=fraud.evaluate(current,sender,recipient,tx.getAmount(),tx.getId());
        tx.reassess(risk.score(),risk.level());
        String action;
        if (risk.level()==RiskLevel.CRITICAL) {
            tx.resolve(TransactionStatus.BLOCKED,"Confirmation reassessment detected critical risk. No funds moved.",now);
            action="BLOCK";
        } else if (invalid!=null) {
            tx.resolve(TransactionStatus.FAILED,invalid,now);
            action="REJECT";
        } else {
            sender.debit(tx.getAmount());
            if (recipient!=null) recipient.credit(tx.getAmount());
            tx.resolve(TransactionStatus.COMPLETED,"Authorized with the initiating user's password and completed after reassessment.",now);
            action="APPROVE";
        }
        FraudAnalysis assessment=new FraudAnalysis(tx,risk.score(),risk.level(),action);
        assessment.confirmation();
        risk.findings().forEach(f->assessment.add(new FraudRuleResult(f.rule(),f.reason(),f.severity(),f.points())));
        analyses.save(assessment);
        journal.changed(actor.getId(),"CONFIRMATION_OUTCOME",tx,"PENDING_CONFIRMATION",tx.getOutcomeReason());
        return views.response(tx,actor);
    }

    @Transactional(noRollbackFor=ApiException.class)
    public TransactionResponse cancel(User actor,Long id) {
        Transaction tx=owned(actor,id);
        if ((tx.getStatus()==TransactionStatus.PENDING_CONFIRMATION || tx.getStatus()==TransactionStatus.AWAITING_REVIEW) && !expire(tx,Instant.now())) {
            String previous=tx.getStatus().name();
            tx.resolve(TransactionStatus.CANCELLED,"Cancelled by the initiating user. No funds moved.",Instant.now());
            journal.changed(actor.getId(),"CANCELLED",tx,previous,tx.getOutcomeReason());
        }
        return views.response(tx,actor);
    }

    private Transaction owned(User actor,Long id) {
        Transaction tx=transactions.findByIdForUpdate(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"TRANSACTION_NOT_FOUND","Transaction not found"));
        if (!tx.getInitiatedBy().getId().equals(actor.getId()))
            throw new ApiException(HttpStatus.FORBIDDEN,"CONFIRMATION_FORBIDDEN","Only the initiating user can confirm or cancel this transaction");
        return tx;
    }
    private boolean expire(Transaction tx,Instant now) {
        if (!tx.expired(now)) return false;
        String previous=tx.getStatus().name();
        tx.resolve(TransactionStatus.EXPIRED,"Authorization period expired. No funds moved.",now);
        journal.changed(null,"EXPIRED",tx,previous,tx.getOutcomeReason());
        return true;
    }
    private Account locked(Long id) {
        return accounts.findByIdForUpdate(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND","Account not found"));
    }
}
