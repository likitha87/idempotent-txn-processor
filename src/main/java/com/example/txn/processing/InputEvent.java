package com.example.txn.processing;

import com.example.txn.model.TransactionRecord;

/**
 * One line in the audit trail: what happened to one input record. Every input line gets exactly one,
 * so totals always reconcile with the input size.
 *
 * @param record the transaction state (ACCEPTED) or the original transaction (DUPLICATE); null for INVALID
 */
public record InputEvent(
        int inputOrder,
        String transactionId,
        String requestId,
        Long sequence,
        Kind kind,
        String detail,
        TransactionRecord record) {

    public enum Kind { ACCEPTED, DUPLICATE, INVALID }
}
