package com.example.txn.store;

import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionRequest;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentMap<String, TransactionRecord> records = new ConcurrentHashMap<>();

    @Override
    public ClaimResult claim(TransactionRequest request) {
        TransactionRecord fresh = new TransactionRecord(request);
        // putIfAbsent is atomic: among concurrent callers exactly one gets null back and wins the claim.
        TransactionRecord existing = records.putIfAbsent(request.transactionId(), fresh);
        return existing == null ? new ClaimResult(true, fresh) : new ClaimResult(false, existing);
    }

    @Override
    public Optional<TransactionRecord> find(String transactionId) {
        return Optional.ofNullable(records.get(transactionId));
    }

    @Override
    public Collection<TransactionRecord> all() {
        return records.values();
    }
}
