package com.example.txn.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable processing state for one unique business transaction (keyed by transactionId).
 * Mutated only by the processor while holding its lock; fields are volatile so reports can read safely.
 */
public final class TransactionRecord {
    private final TransactionRequest request;
    private volatile TransactionStatus status = TransactionStatus.RECEIVED;
    private volatile int attempts;
    private volatile String detail = "";
    private volatile Instant updatedAt = Instant.now();
    private final List<String> duplicateRequestIds = Collections.synchronizedList(new ArrayList<>());

    public TransactionRecord(TransactionRequest request) {
        this.request = request;
    }

    public TransactionRequest request() { return request; }
    public TransactionStatus status() { return status; }
    public int attempts() { return attempts; }
    public String detail() { return detail; }
    public Instant updatedAt() { return updatedAt; }
    public List<String> duplicateRequestIds() { return List.copyOf(duplicateRequestIds); }

    public void transitionTo(TransactionStatus next, String detail) {
        this.status = next;
        this.detail = detail == null ? "" : detail;
        this.updatedAt = Instant.now();
    }

    public void incrementAttempts() { attempts++; }

    public void addDuplicateRequestId(String requestId) { duplicateRequestIds.add(requestId); }
}
