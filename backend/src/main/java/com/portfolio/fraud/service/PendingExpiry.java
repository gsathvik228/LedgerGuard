package com.portfolio.fraud.service;
import com.portfolio.fraud.repository.TransactionRepository;
import java.time.Instant;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @EnableScheduling
public class PendingExpiry {
    private final TransactionRepository transactions;
    public PendingExpiry(TransactionRepository transactions) { this.transactions=transactions; }
    @Scheduled(fixedDelay=5000)
    @Transactional
    public void expire() { transactions.expirePending(Instant.now()); }
}
