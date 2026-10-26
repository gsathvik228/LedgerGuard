package com.portfolio.fraud.service;

import com.portfolio.fraud.entity.TransactionStatus;

/** Synchronous event: resource state and ledger outcome commit or roll back together. */
public record TransactionStateChanged(Long transactionId,TransactionStatus status) {}
