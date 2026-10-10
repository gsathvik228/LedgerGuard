package com.portfolio.fraud.service;

import com.portfolio.fraud.dto.TransactionDtos.*;
import com.portfolio.fraud.dto.FraudDtos.*;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.exception.ApiException;
import com.portfolio.fraud.fraud.FraudDetectionService;
import com.portfolio.fraud.repository.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class TransactionService {
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final FraudAnalysisRepository analyses;
    private final FraudDetectionService fraud;

    public TransactionService(AccountRepository a, TransactionRepository t, FraudAnalysisRepository fa, FraudDetectionService f) {
        accounts=a; transactions=t; analyses=fa; fraud=f;
    }

    @Transactional
    public TransactionResponse transfer(User user, TransferRequest request) {
        if (request.senderAccountId().equals(request.recipientAccountId()))
            throw bad("SAME_ACCOUNT","Cannot transfer to the same account");
        // All transfers take account locks in the same order, including reciprocal transfers.
        long first=Math.min(request.senderAccountId(),request.recipientAccountId());
        long second=Math.max(request.senderAccountId(),request.recipientAccountId());
        Account low=locked(first), high=locked(second);
        Account sender=request.senderAccountId().equals(low.getId())?low:high;
        Account recipient=request.recipientAccountId().equals(low.getId())?low:high;
        requireOwner(user,sender);
        requireActive(sender); requireActive(recipient);
        if (!sender.getCurrency().equals(recipient.getCurrency()))
            throw bad("CURRENCY_MISMATCH","Transfers require accounts in the same currency");
        requireFunds(sender,request.amount());
        var risk=fraud.evaluate(user,sender,recipient,request.amount());
        Transaction tx=save(sender,recipient,user,TransactionType.TRANSFER,request.amount(),risk,request.description());
        if (tx.getStatus()==TransactionStatus.COMPLETED) {
            sender.debit(request.amount()); recipient.credit(request.amount());
        }
        return map(tx);
    }

    @Transactional
    public TransactionResponse withdrawal(User user, MoneyRequest request) {
        Account account=owned(user,request.accountId());
        requireActive(account); requireFunds(account,request.amount());
        var risk=fraud.evaluate(user,account,null,request.amount());
        Transaction tx=save(account,null,user,TransactionType.WITHDRAWAL,request.amount(),risk,request.description());
        if (tx.getStatus()==TransactionStatus.COMPLETED) account.debit(request.amount());
        return map(tx);
    }

    @Transactional
    public TransactionResponse deposit(User user, MoneyRequest request) {
        Account account=owned(user,request.accountId());
        requireActive(account);
        var risk=new FraudDetectionService.Result(0,RiskLevel.LOW,List.of());
        Transaction tx=save(null,account,user,TransactionType.DEPOSIT,request.amount(),risk,request.description());
        account.credit(request.amount());
        return map(tx);
    }

    private Transaction save(Account sender,Account recipient,User user,TransactionType type,BigDecimal amount,
                             FraudDetectionService.Result risk,String description) {
        TransactionStatus status=risk.level()==RiskLevel.CRITICAL?TransactionStatus.BLOCKED:TransactionStatus.COMPLETED;
        Transaction tx=transactions.save(new Transaction(sender,recipient,user,type,amount,status,risk.level(),risk.score(),description));
        FraudAnalysis analysis=new FraudAnalysis(tx,risk.score(),risk.level(),status==TransactionStatus.BLOCKED?"BLOCK":"APPROVE");
        risk.findings().forEach(f->analysis.add(new FraudRuleResult(f.rule(),f.reason(),f.severity(),f.points())));
        analyses.save(analysis);
        return tx;
    }

    public List<AccountResponse> accounts(User user) {
        return accounts.findByUserId(user.getId()).stream().map(a->new AccountResponse(
            a.getId(),a.getAccountNumber(),a.getAccountType(),a.getBalance(),a.getCurrency(),a.getStatus().name())).toList();
    }

    public Page<TransactionResponse> history(User user,TransactionType type,TransactionStatus status,RiskLevel risk,
                                             Instant from,Instant to,int page,int size) {
        return search(user.getId(),type,status,risk,from,to,page,size,false);
    }

    public Page<TransactionResponse> monitoring(TransactionType type,TransactionStatus status,RiskLevel risk,
                                                Instant from,Instant to,int page,int size,boolean suspicious) {
        return search(null,type,status,risk,from,to,page,size,suspicious);
    }

    private Page<TransactionResponse> search(Long userId,TransactionType type,TransactionStatus status,RiskLevel risk,
                                             Instant from,Instant to,int page,int size,boolean suspicious) {
        if (from!=null && to!=null && from.isAfter(to)) throw bad("INVALID_DATE_RANGE","Start must be before end");
        if (page<0 || size<1 || size>100) throw bad("INVALID_PAGE","Page must be nonnegative and size between 1 and 100");
        return transactions.search(userId,type,status,risk,from,to,suspicious,
            PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt","id"))).map(this::map);
    }

    private Transaction visible(User user,Long id) {
        Transaction tx=transactions.findById(id).orElseThrow(this::notFound);
        boolean allowed=user.getRole()==Role.ADMIN || tx.getInitiatedBy().getId().equals(user.getId())
            || (tx.getSenderAccount()!=null && tx.getSenderAccount().getUser().getId().equals(user.getId()))
            || (tx.getRecipientAccount()!=null && tx.getRecipientAccount().getUser().getId().equals(user.getId()));
        if (!allowed) throw notFound();
        return tx;
    }

    public TransactionResponse detail(User user,Long id) { return map(visible(user,id)); }

    public AnalysisResponse fraudDetail(User user,Long id) {
        visible(user,id);
        FraudAnalysis analysis=analyses.findByTransactionId(id).orElseThrow(this::notFound);
        return new AnalysisResponse(analysis.getRiskScore(),analysis.getRiskLevel().name(),analysis.getAction(),analysis.getCreatedAt(),
            analysis.getRuleResults().stream().map(r->new RuleResponse(r.getRuleName(),r.getSeverity().name(),r.getReason(),r.getScoreContribution())).toList());
    }

    private Account locked(Long id) {
        return accounts.findByIdForUpdate(id).orElseThrow(()->bad("ACCOUNT_NOT_FOUND","Account does not exist"));
    }
    private Account owned(User user,Long id) { Account account=locked(id); requireOwner(user,account); return account; }
    private void requireOwner(User user,Account account) {
        if (!account.getUser().getId().equals(user.getId()))
            throw new ApiException(HttpStatus.FORBIDDEN,"ACCOUNT_FORBIDDEN","You cannot use this account");
    }
    private void requireActive(Account account) {
        if (account.getStatus()!=AccountStatus.ACTIVE) throw bad("INACTIVE_ACCOUNT","Account is inactive");
    }
    private void requireFunds(Account account,BigDecimal amount) {
        if (account.getBalance().compareTo(amount)<0) throw bad("INSUFFICIENT_BALANCE","Insufficient balance");
    }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"TRANSACTION_NOT_FOUND","Transaction not found"); }
    private ApiException bad(String code,String message) { return new ApiException(HttpStatus.BAD_REQUEST,code,message); }
    private TransactionResponse map(Transaction tx) {
        Account account=tx.getSenderAccount()!=null?tx.getSenderAccount():tx.getRecipientAccount();
        return new TransactionResponse(tx.getId(),tx.getType().name(),tx.getAmount(),tx.getStatus().name(),
            tx.getRiskLevel().name(),tx.getRiskScore(),tx.getDescription(),tx.getCreatedAt(),
            tx.getSenderAccount()==null?null:tx.getSenderAccount().getId(),
            tx.getRecipientAccount()==null?null:tx.getRecipientAccount().getId(),account.getCurrency());
    }
}
