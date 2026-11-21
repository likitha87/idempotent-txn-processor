package com.example.txn;

import com.example.txn.ledger.InMemoryLedger;
import com.example.txn.model.TransactionStatus;
import com.example.txn.processing.FaultInjector;
import com.example.txn.processing.FaultInjector.Point;
import com.example.txn.processing.RetryPolicy;
import com.example.txn.processing.ScriptedFaultInjector;
import com.example.txn.processing.TransactionProcessor;
import com.example.txn.report.ProcessingReport;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryResilienceTest {

    @Test
    void transientFailureBeforeCommitIsRetriedThenProcessed() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger,
                new ScriptedFaultInjector().failTimes("TXN-1", Point.BEFORE_COMMIT, 1), 3, ms -> { });

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "500"));

        ProcessingReport.Row row = Fixtures.rowOf(p.report(), "TXN-1", "REQ-1");
        assertEquals(TransactionStatus.PROCESSED, row.status());
        assertEquals(2, row.attempts());
        assertEquals(new BigDecimal("500"), ledger.balance("ACC-1", "USD"));
        assertEquals(1, p.report().retriesPerformed());
    }

    @Test
    void lostAcknowledgementAfterCommitDoesNotApplyTheEffectTwice() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger,
                new ScriptedFaultInjector().failTimes("TXN-1", Point.AFTER_COMMIT, 1), 3, ms -> { });

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "500"));

        assertEquals(TransactionStatus.PROCESSED, Fixtures.statusOf(p.report(), "TXN-1", "REQ-1"));
        assertEquals(new BigDecimal("500"), ledger.balance("ACC-1", "USD")); // 500, not 1000
        assertEquals(1, ledger.entries().size());
    }

    @Test
    void retryExhaustionBeforeCommitEndsInFailedWithNoEffect() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger,
                new ScriptedFaultInjector().failTimes("TXN-1", Point.BEFORE_COMMIT, 99), 3, ms -> { });

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "500"));

        ProcessingReport.Row row = Fixtures.rowOf(p.report(), "TXN-1", "REQ-1");
        assertEquals(TransactionStatus.FAILED, row.status());
        assertEquals(3, row.attempts()); // never exceeds the configured maximum
        assertEquals(BigDecimal.ZERO, ledger.balance("ACC-1", "USD"));
        assertTrue(ledger.entries().isEmpty());
    }

    @Test
    void retryExhaustionAfterCommitIsReconciledToProcessedNotFalselyFailed() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger,
                new ScriptedFaultInjector().failTimes("TXN-1", Point.AFTER_COMMIT, 99), 3, ms -> { });

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "500"));

        ProcessingReport.Row row = Fixtures.rowOf(p.report(), "TXN-1", "REQ-1");
        assertEquals(TransactionStatus.PROCESSED, row.status());
        assertTrue(row.detail().contains("reconciled"));
        assertEquals(new BigDecimal("500"), ledger.balance("ACC-1", "USD"));
        assertEquals(1, ledger.entries().size());
    }

    @Test
    void failedTransactionDoesNotBlockLaterSequences() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger,
                new ScriptedFaultInjector().failTimes("TXN-1", Point.BEFORE_COMMIT, 99), 2, ms -> { });

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));
        p.submit(Fixtures.raw(2, "TXN-2", "REQ-2", "2", "ACC-1", "CREDIT", "20"));

        ProcessingReport report = p.report();
        assertEquals(TransactionStatus.FAILED, Fixtures.statusOf(report, "TXN-1", "REQ-1"));
        assertEquals(TransactionStatus.PROCESSED, Fixtures.statusOf(report, "TXN-2", "REQ-2"));
    }

    @Test
    void failedTransactionResentLaterIsStillTreatedAsDuplicate() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger,
                new ScriptedFaultInjector().failTimes("TXN-1", Point.BEFORE_COMMIT, 99), 2, ms -> { });

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));
        p.submit(Fixtures.raw(2, "TXN-1", "REQ-1-AGAIN", "1", "ACC-1", "CREDIT", "10"));

        // A FAILED transaction is recoverable through an explicit replay path (see DESIGN.md), not by silent resubmission.
        assertEquals(TransactionStatus.DUPLICATE, Fixtures.statusOf(p.report(), "TXN-1", "REQ-1-AGAIN"));
        assertTrue(ledger.entries().isEmpty());
    }

    @Test
    void insufficientFundsFailsImmediatelyWithoutRetriesOrPartialState() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "DEBIT", "40"));

        ProcessingReport.Row row = Fixtures.rowOf(p.report(), "TXN-1", "REQ-1");
        assertEquals(TransactionStatus.FAILED, row.status());
        assertEquals(1, row.attempts()); // permanent failure: never retried
        assertEquals(BigDecimal.ZERO, ledger.balance("ACC-1", "USD"));
        assertTrue(ledger.entries().isEmpty());
    }

    @Test
    void backoffGrowsExponentiallyBetweenAttemptsAndIsCapped() {
        List<Long> sleeps = new ArrayList<>();
        TransactionProcessor p = Fixtures.processor(new InMemoryLedger(),
                new ScriptedFaultInjector().failTimes("TXN-1", Point.BEFORE_COMMIT, 99), 4, sleeps::add);

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));

        assertEquals(List.of(100L, 200L, 400L), sleeps); // no sleep after the final attempt
        assertEquals(2_000L, new RetryPolicy(10, 100, 2_000).backoffAfterAttempt(9));
    }

    @Test
    void interruptedBackoffStopsFurtherRetriesAndPreservesInterruptFlag() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger,
                new ScriptedFaultInjector().failTimes("TXN-1", Point.BEFORE_COMMIT, 99),
                3, ms -> Thread.currentThread().interrupt());

        try {
            p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));

            ProcessingReport.Row row = Fixtures.rowOf(p.report(), "TXN-1", "REQ-1");
            assertEquals(TransactionStatus.FAILED, row.status());
            assertEquals(1, row.attempts()); // interruption prevents the next attempt
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // clear the test thread's interrupt flag
        }
    }

    @Test
    void unexpectedErrorsFailSafelyWithoutLeakingTheirMessage() {
        FaultInjector exploding = (point, txnId, attempt) -> {
            if (point == Point.BEFORE_COMMIT) {
                throw new IllegalStateException("customer card 4111111111111111 leaked");
            }
        };
        TransactionProcessor p = Fixtures.processor(new InMemoryLedger(), exploding, 3, ms -> { });

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));

        ProcessingReport.Row row = Fixtures.rowOf(p.report(), "TXN-1", "REQ-1");
        assertEquals(TransactionStatus.FAILED, row.status());
        assertFalse(row.detail().contains("4111"));
        assertTrue(row.detail().contains("IllegalStateException"));
        assertEquals(1, row.attempts()); // unknown errors are not blindly retried
    }
}
