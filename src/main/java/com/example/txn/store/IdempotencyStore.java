package com.example.txn.store;

import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionRequest;

import java.util.Collection;
import java.util.Optional;

/**
 * Transaction state + deduplication store, keyed by the business transactionId.
 * Production swap: a table with a UNIQUE constraint on transaction_id (INSERT ... ON CONFLICT DO NOTHING).
 */
public interface IdempotencyStore {

    /** Result of an atomic claim. {@code firstSeen} is true for exactly one caller per transactionId. */
    record ClaimResult(boolean firstSeen, TransactionRecord record) { }

    /** Atomically registers the transaction if unseen; otherwise returns the existing record. */
    ClaimResult claim(TransactionRequest request);

    Optional<TransactionRecord> find(String transactionId);

    Collection<TransactionRecord> all();
}
