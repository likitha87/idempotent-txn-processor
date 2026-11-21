package com.example.txn.source;

import com.example.txn.model.RawTransaction;

import java.util.List;

/** Test/demo source backed by a list. */
public final class InMemoryTransactionSource implements TransactionSource {
    private final List<RawTransaction> records;

    public InMemoryTransactionSource(List<RawTransaction> records) {
        this.records = List.copyOf(records);
    }

    @Override
    public List<RawTransaction> read() {
        return records;
    }
}
