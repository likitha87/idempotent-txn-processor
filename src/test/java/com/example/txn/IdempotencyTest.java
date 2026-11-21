package com.example.txn;

import com.example.txn.ledger.InMemoryLedger;
import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionStatus;
import com.example.txn.model.TransactionType;
import com.example.txn.processing.TransactionProcessor;
import com.example.txn.report.ProcessingReport;
import com.example.txn.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyTest {

    @Test
    void duplicateAfterProcessingIsIgnoredAndBalanceIsUnchanged() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"));
        p.submit(Fixtures.raw(2, "TXN-1", "REQ-1-RETRY", "1", "ACC-1", "CREDIT", "100"));
        p.submit(Fixtures.raw(3, "TXN-1", "REQ-1-RETRY2", "1", "ACC-1", "CREDIT", "100"));

        ProcessingReport report = p.report();
        assertEquals(new BigDecimal("100"), ledger.balance("ACC-1", "USD"));
        assertEquals(1, ledger.entries().size());
        assertEquals(1, report.count(TransactionStatus.PROCESSED));
        assertEquals(2, report.count(TransactionStatus.DUPLICATE));
    }

    @Test
    void duplicateArrivingLaterInTheBatchIsDetected() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));
        p.submit(Fixtures.raw(2, "TXN-2", "REQ-2", "2", "ACC-1", "CREDIT", "20"));
        p.submit(Fixtures.raw(3, "TXN-3", "REQ-3", "3", "ACC-1", "CREDIT", "30"));
        p.submit(Fixtures.raw(4, "TXN-1", "REQ-1-LATE", "1", "ACC-1", "CREDIT", "10"));

        assertEquals(TransactionStatus.DUPLICATE, Fixtures.statusOf(p.report(), "TXN-1", "REQ-1-LATE"));
        assertEquals(new BigDecimal("60"), ledger.balance("ACC-1", "USD"));
    }

    @Test
    void duplicateOfATransactionStillWaitingInTheBufferIsIgnored() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        p.submit(Fixtures.raw(1, "TXN-2", "REQ-2", "2", "ACC-1", "CREDIT", "20")); // buffered, waits for seq 1
        p.submit(Fixtures.raw(2, "TXN-2", "REQ-2-DUP", "2", "ACC-1", "CREDIT", "20")); // duplicate while pending
        p.submit(Fixtures.raw(3, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"));      // releases both

        assertEquals(new BigDecimal("30"), ledger.balance("ACC-1", "USD"));
        assertEquals(2, ledger.entries().size());
        assertEquals(TransactionStatus.DUPLICATE, Fixtures.statusOf(p.report(), "TXN-2", "REQ-2-DUP"));
    }

    @Test
    void duplicateWithDifferentPayloadIsIgnoredAndFlagged() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = Fixtures.processor(ledger);

        p.submit(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"));
        p.submit(Fixtures.raw(2, "TXN-1", "REQ-X", "1", "ACC-1", "CREDIT", "999")); // same id, different amount

        ProcessingReport.Row dup = Fixtures.rowOf(p.report(), "TXN-1", "REQ-X");
        assertEquals(TransactionStatus.DUPLICATE, dup.status());
        assertTrue(dup.detail().contains("payload differs"));
        assertEquals(new BigDecimal("100"), ledger.balance("ACC-1", "USD")); // original wins
    }

    @Test
    void sameTransactionAppliedToLedgerTwiceHasOneEffect() throws Exception {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionRequest req = new TransactionRequest("TXN-1", "REQ-1", 1, "ACC-1",
                TransactionType.CREDIT, new BigDecimal("100"), "USD");
        ledger.apply(req);
        ledger.apply(req);
        assertEquals(new BigDecimal("100"), ledger.balance("ACC-1", "USD"));
        assertEquals(1, ledger.entries().size());
    }

    @Test
    void concurrentClaimsOfTheSameTransactionHaveExactlyOneWinner() throws Exception {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        TransactionRequest req = new TransactionRequest("TXN-1", "REQ-1", 1, "ACC-1",
                TransactionType.CREDIT, new BigDecimal("100"), "USD");
        int threads = 32;
        AtomicInteger winners = new AtomicInteger();
        runConcurrently(threads, () -> {
            if (store.claim(req).firstSeen()) {
                winners.incrementAndGet();
            }
            return null;
        });
        assertEquals(1, winners.get());
        assertEquals(1, store.all().size());
    }

    @Test
    void concurrentLedgerAppliesOfTheSameTransactionPostOnce() throws Exception {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionRequest req = new TransactionRequest("TXN-1", "REQ-1", 1, "ACC-1",
                TransactionType.CREDIT, new BigDecimal("100"), "USD");
        runConcurrently(32, () -> {
            ledger.apply(req);
            return null;
        });
        assertEquals(new BigDecimal("100"), ledger.balance("ACC-1", "USD"));
        assertEquals(1, ledger.entries().size());
    }

    private static void runConcurrently(int threads, Callable<Void> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }
}
