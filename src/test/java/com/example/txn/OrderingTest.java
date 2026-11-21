package com.example.txn;

import com.example.txn.ledger.InMemoryLedger;
import com.example.txn.model.TransactionStatus;
import com.example.txn.processing.TransactionProcessor;
import com.example.txn.report.ProcessingReport;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderingTest {

    @Test
    void debitArrivingBeforeItsFundingCreditWaitsInsteadOfOverdrawing() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        p.submit(Fixtures.raw(1, "TXN-2", "REQ-2", "2", "ACC-1", "DEBIT", "40"));
        assertEquals(TransactionStatus.PENDING_SEQUENCE, Fixtures.statusOf(p.report(), "TXN-2", "REQ-2"));
        assertEquals(BigDecimal.ZERO, ledger.balance("ACC-1", "USD")); // nothing applied yet

        p.submit(Fixtures.raw(2, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"));

        ProcessingReport report = p.report();
        assertEquals(TransactionStatus.PROCESSED, Fixtures.statusOf(report, "TXN-2", "REQ-2"));
        assertEquals(TransactionStatus.PROCESSED, Fixtures.statusOf(report, "TXN-1", "REQ-1"));
        assertEquals(new BigDecimal("60"), ledger.balance("ACC-1", "USD"));
    }

    @Test
    void fullyReversedBatchIsAppliedInSequenceOrder() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        for (int seq = 5; seq >= 1; seq--) {
            p.submit(Fixtures.raw(6 - seq, "TXN-" + seq, "REQ-" + seq, String.valueOf(seq), "ACC-1", "CREDIT", "10"));
        }

        List<String> applied = ledger.entries().stream().map(e -> e.transactionId()).collect(Collectors.toList());
        assertEquals(List.of("TXN-1", "TXN-2", "TXN-3", "TXN-4", "TXN-5"), applied);
        assertEquals(5, p.report().count(TransactionStatus.PROCESSED));
    }

    @Test
    void missingSequenceKeepsLaterRecordsPendingAndIsReported() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));
        p.submit(Fixtures.raw(2, "TXN-4", "REQ-4", "4", "ACC-1", "CREDIT", "10"));

        ProcessingReport report = p.report();
        assertEquals(TransactionStatus.PENDING_SEQUENCE, Fixtures.statusOf(report, "TXN-4", "REQ-4"));
        assertEquals(List.of(2L, 3L), report.missingSequences());
        assertEquals(1, ledger.entries().size());
    }

    @Test
    void lateArrivalReleasesEverythingBufferedBehindIt() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));
        p.submit(Fixtures.raw(2, "TXN-3", "REQ-3", "3", "ACC-1", "CREDIT", "10"));
        p.submit(Fixtures.raw(3, "TXN-4", "REQ-4", "4", "ACC-1", "CREDIT", "10"));
        assertEquals(1, ledger.entries().size());

        p.submit(Fixtures.raw(4, "TXN-2", "REQ-2", "2", "ACC-1", "CREDIT", "10"));

        assertEquals(4, ledger.entries().size());
        assertTrue(p.report().missingSequences().isEmpty());
    }

    @Test
    void invalidRecordIsIsolatedAndDoesNotBlockLaterSequences() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));
        p.submit(Fixtures.raw(2, "TXN-2", "REQ-2", "2", "ACC-1", "DEBIT", "-5"));   // invalid, owns seq 2
        p.submit(Fixtures.raw(3, "TXN-3", "REQ-3", "3", "ACC-1", "CREDIT", "10"));

        ProcessingReport report = p.report();
        assertEquals(TransactionStatus.REJECTED, Fixtures.statusOf(report, "TXN-2", "REQ-2"));
        assertEquals(TransactionStatus.PROCESSED, Fixtures.statusOf(report, "TXN-3", "REQ-3"));
        assertEquals(new BigDecimal("20"), ledger.balance("ACC-1", "USD"));
    }

    @Test
    void invalidRecordArrivingBeforeItsPredecessorsStillDoesNotStallTheStream() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        p.submit(Fixtures.raw(1, "TXN-3", "REQ-3", "3", "ACC-1", "CREDIT", "10"));
        p.submit(Fixtures.raw(2, "TXN-2", "REQ-2", "2", "ACC-1", "CREDIT", "abc")); // invalid, owns seq 2
        p.submit(Fixtures.raw(3, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));

        assertEquals(2, ledger.entries().size());
        assertEquals(TransactionStatus.PROCESSED, Fixtures.statusOf(p.report(), "TXN-3", "REQ-3"));
    }

    @Test
    void validRecordMayStillClaimASlotThatAnInvalidRecordOnlyReserved() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        p.submit(Fixtures.raw(1, "BAD", "REQ-B", "2", "ACC-1", "CREDIT", "-1"));   // invalid with seq 2
        p.submit(Fixtures.raw(2, "TXN-2", "REQ-2", "2", "ACC-1", "CREDIT", "10")); // the real seq 2
        p.submit(Fixtures.raw(3, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));

        assertEquals(2, ledger.entries().size());
    }

    @Test
    void sequenceSlotAlreadyConsumedIsRejectedAsStale() {
        TransactionProcessor p = Fixtures.processor();
        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));
        p.submit(Fixtures.raw(2, "TXN-2", "REQ-2", "2", "ACC-1", "CREDIT", "10"));

        p.submit(Fixtures.raw(3, "TXN-9", "REQ-9", "2", "ACC-1", "CREDIT", "10")); // new txn, used-up slot

        assertEquals(TransactionStatus.REJECTED, Fixtures.statusOf(p.report(), "TXN-9", "REQ-9"));
    }

    @Test
    void twoDifferentTransactionsClaimingTheSameSequenceConflict() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        p.submit(Fixtures.raw(1, "TXN-A", "REQ-A", "3", "ACC-1", "CREDIT", "10"));
        p.submit(Fixtures.raw(2, "TXN-B", "REQ-B", "3", "ACC-1", "CREDIT", "10"));

        ProcessingReport report = p.report();
        assertEquals(TransactionStatus.PENDING_SEQUENCE, Fixtures.statusOf(report, "TXN-A", "REQ-A"));
        assertEquals(TransactionStatus.REJECTED, Fixtures.statusOf(report, "TXN-B", "REQ-B"));
    }
}
