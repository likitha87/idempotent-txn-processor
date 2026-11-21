package com.example.txn.model;

/**
 * Lifecycle of a transaction.
 *
 * <pre>
 * RECEIVED -> PENDING_SEQUENCE -> PROCESSING -> PROCESSED
 *                                     |  ^
 *                                     v  |
 *                               RETRY_PENDING -> FAILED   (retries exhausted / business rule)
 * RECEIVED -> REJECTED   (stale or conflicting sequence)
 * </pre>
 *
 * DUPLICATE and REJECTED (invalid input) describe an <em>input record</em> outcome rather than a state of the
 * original transaction; they are reported in the same summary so every input line is accounted for.
 */
public enum TransactionStatus {
    RECEIVED,
    PENDING_SEQUENCE,
    PROCESSING,
    RETRY_PENDING,
    PROCESSED,
    FAILED,
    DUPLICATE,
    REJECTED
}
