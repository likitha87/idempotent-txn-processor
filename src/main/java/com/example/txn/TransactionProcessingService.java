package com.example.txn;

import com.example.txn.model.RawTransaction;
import com.example.txn.processing.TransactionProcessor;
import com.example.txn.report.ProcessingReport;
import com.example.txn.source.TransactionSource;
import com.example.txn.util.StructuredLog;

import java.io.IOException;
import java.util.List;

/**
 * The "background job": pulls a batch from a source and feeds the processor.
 * A queue-driven deployment would call {@code processor.submit} from a message listener instead.
 */
public final class TransactionProcessingService {

    private final TransactionProcessor processor;

    public TransactionProcessingService(TransactionProcessor processor) {
        this.processor = processor;
    }

    public ProcessingReport run(TransactionSource source) throws IOException {
        List<RawTransaction> batch = source.read();
        StructuredLog.info("BATCH_STARTED", "records", batch.size());
        for (RawTransaction raw : batch) {
            processor.submit(raw);
        }
        ProcessingReport report = processor.report();
        StructuredLog.info("BATCH_FINISHED", "records", report.totalInputs(),
                "processed", report.count(com.example.txn.model.TransactionStatus.PROCESSED),
                "failed", report.count(com.example.txn.model.TransactionStatus.FAILED),
                "duplicates", report.count(com.example.txn.model.TransactionStatus.DUPLICATE),
                "rejected", report.count(com.example.txn.model.TransactionStatus.REJECTED),
                "pending", report.count(com.example.txn.model.TransactionStatus.PENDING_SEQUENCE));
        return report;
    }
}
