package com.example.txn.model;

import java.math.BigDecimal;

/** Validated request created only by RequestValidator. */
public record TransactionRequest(String transactionId, String requestId, long sequenceNumber,
        String accountId, TransactionType transactionType, BigDecimal amount, String currency,
        String reversalOfTransactionId) {

    public TransactionRequest(String transactionId, String requestId, long sequenceNumber, String accountId,
                              TransactionType transactionType, BigDecimal amount, String currency) {
        this(transactionId, requestId, sequenceNumber, accountId, transactionType, amount, currency, null);
    }

    /** Fingerprint excludes requestId because delivery retries may use a new request id. */
    public String fingerprint() {
        return String.join("|", accountId, transactionType.name(), amount.stripTrailingZeros().toPlainString(),
                currency, Long.toString(sequenceNumber), reversalOfTransactionId == null ? "" : reversalOfTransactionId);
    }
}
