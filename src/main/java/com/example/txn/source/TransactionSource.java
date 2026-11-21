package com.example.txn.source;

import com.example.txn.model.RawTransaction;

import java.io.IOException;
import java.util.List;

/**
 * Input abstraction (L2-FR-01 / FR-10). Today: in-memory list or CSV file. Tomorrow: a Kafka/SQS consumer,
 * a DB-polling job or an HTTP endpoint adapter, with no change to the processing logic.
 */
public interface TransactionSource {
    List<RawTransaction> read() throws IOException;
}
