package com.example.txn.model;

/** Untrusted source record. All external values remain strings until validation succeeds. */
public record RawTransaction(
        int inputOrder, String transactionId, String requestId, String sequence,
        String accountId, String transactionType, String amount, String currency,
        String reversalOfTransactionId, String parseError) {

    public RawTransaction(int inputOrder, String transactionId, String requestId, String sequence,
                          String accountId, String transactionType, String amount, String currency) {
        this(inputOrder, transactionId, requestId, sequence, accountId, transactionType, amount, currency, null, null);
    }

    public RawTransaction(int inputOrder, String transactionId, String requestId, String sequence,
                          String accountId, String transactionType, String amount, String currency, String parseError) {
        this(inputOrder, transactionId, requestId, sequence, accountId, transactionType, amount, currency, null, parseError);
    }
}
