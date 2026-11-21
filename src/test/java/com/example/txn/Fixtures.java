package com.example.txn;

import com.example.txn.ledger.InMemoryLedger;
import com.example.txn.ledger.Ledger;
import com.example.txn.model.RawTransaction;
import com.example.txn.model.TransactionStatus;
import com.example.txn.processing.FaultInjector;
import com.example.txn.processing.RetryPolicy;
import com.example.txn.processing.Sleeper;
import com.example.txn.processing.TransactionProcessor;
import com.example.txn.report.ProcessingReport;
import com.example.txn.store.InMemoryIdempotencyStore;
import com.example.txn.validation.RequestValidator;

/** Shared test builders. */
final class Fixtures {

    private Fixtures() { }

    static RawTransaction raw(int order, String txnId, String reqId, String seq,
                              String account, String type, String amount) {
        return new RawTransaction(order, txnId, reqId, seq, account, type, amount, "USD");
    }

    static TransactionProcessor processor(Ledger ledger, FaultInjector faults, int maxAttempts, Sleeper sleeper) {
        return new TransactionProcessor(new RequestValidator(), new InMemoryIdempotencyStore(), ledger,
                faults, new RetryPolicy(maxAttempts, 100, 2_000), sleeper, 1);
    }

    static TransactionProcessor processor(Ledger ledger) {
        return processor(ledger, FaultInjector.none(), 3, Sleeper.NO_OP);
    }

    static TransactionProcessor processor() {
        return processor(new InMemoryLedger());
    }

    static TransactionStatus statusOf(ProcessingReport report, String txnId, String requestId) {
        return report.rows().stream()
                .filter(r -> r.transactionId().equals(txnId) && r.requestId().equals(requestId))
                .findFirst().orElseThrow().status();
    }

    static ProcessingReport.Row rowOf(ProcessingReport report, String txnId, String requestId) {
        return report.rows().stream()
                .filter(r -> r.transactionId().equals(txnId) && r.requestId().equals(requestId))
                .findFirst().orElseThrow();
    }
}
