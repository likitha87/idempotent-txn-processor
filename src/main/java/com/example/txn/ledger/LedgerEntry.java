package com.example.txn.ledger;

import com.example.txn.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntry(String transactionId, String accountId, TransactionType type, BigDecimal amount,
        String currency, BigDecimal balanceAfter, Instant postedAt, String reversalOfTransactionId) {
    public LedgerEntry(String transactionId, String accountId, TransactionType type, BigDecimal amount,
                       String currency, BigDecimal balanceAfter, Instant postedAt) {
        this(transactionId, accountId, type, amount, currency, balanceAfter, postedAt, null);
    }
}
