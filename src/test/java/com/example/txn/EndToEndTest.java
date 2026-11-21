package com.example.txn;

import com.example.txn.ledger.InMemoryLedger;
import com.example.txn.model.RawTransaction;
import com.example.txn.model.TransactionStatus;
import com.example.txn.processing.FaultInjector.Point;
import com.example.txn.processing.ScriptedFaultInjector;
import com.example.txn.processing.Sleeper;
import com.example.txn.processing.TransactionProcessor;
import com.example.txn.report.ProcessingReport;
import com.example.txn.source.CsvFileTransactionSource;
import com.example.txn.source.InMemoryTransactionSource;
import com.example.txn.util.StructuredLog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndToEndTest {

    /** The six-row dataset from the assessment brief (section 9.8). */
    private static List<RawTransaction> briefDataset() {
        return List.of(
                Fixtures.raw(1, "TXN-2002", "REQ-2", "2", "ACC-201", "DEBIT", "40"),
                Fixtures.raw(2, "TXN-2001", "REQ-1", "1", "ACC-201", "CREDIT", "100"),
                Fixtures.raw(3, "TXN-2002", "REQ-2-DUP", "2", "ACC-201", "DEBIT", "40"),
                Fixtures.raw(4, "TXN-2003", "REQ-3", "3", "ACC-201", "CREDIT", "25"),
                Fixtures.raw(5, "TXN-2004", "REQ-4", "4", "ACC-202", "DEBIT", "-10"),
                Fixtures.raw(6, "TXN-2005", "REQ-5", "5", "ACC-203", "CREDIT", "500"));
    }

    @Test
    void assessmentDatasetProducesTheExpectedOutcomes() throws Exception {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor processor = Fixtures.processor(ledger,
                new ScriptedFaultInjector().failTimes("TXN-2005", Point.AFTER_COMMIT, 1), 3, Sleeper.NO_OP);

        ProcessingReport report = new TransactionProcessingService(processor)
                .run(new InMemoryTransactionSource(briefDataset()));

        assertEquals(6, report.totalInputs());
        assertEquals(4, report.count(TransactionStatus.PROCESSED));
        assertEquals(1, report.count(TransactionStatus.DUPLICATE));
        assertEquals(1, report.count(TransactionStatus.REJECTED));
        assertEquals(0, report.count(TransactionStatus.FAILED));
        assertEquals(0, report.count(TransactionStatus.PENDING_SEQUENCE));
        assertEquals(1, report.retriesPerformed());

        assertEquals(TransactionStatus.PROCESSED, Fixtures.statusOf(report, "TXN-2002", "REQ-2"));
        assertEquals(TransactionStatus.DUPLICATE, Fixtures.statusOf(report, "TXN-2002", "REQ-2-DUP"));
        assertEquals(TransactionStatus.REJECTED, Fixtures.statusOf(report, "TXN-2004", "REQ-4"));
        assertEquals(2, Fixtures.rowOf(report, "TXN-2005", "REQ-5").attempts());

        assertEquals(new BigDecimal("85"), ledger.balance("ACC-201", "USD"));   // 100 - 40 + 25
        assertEquals(new BigDecimal("500"), ledger.balance("ACC-203", "USD"));  // credited once despite the retry
        assertEquals(BigDecimal.ZERO, ledger.balance("ACC-202", "USD"));        // invalid debit never touched it
        assertEquals(4, ledger.entries().size());
    }

    @Test
    void everyInputIsAccountedForInTheSummary() throws Exception {
        TransactionProcessor processor = Fixtures.processor();
        ProcessingReport report = new TransactionProcessingService(processor)
                .run(new InMemoryTransactionSource(briefDataset()));

        long total = report.countsByStatus().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(report.totalInputs(), total);
    }

    @Test
    void reprocessingTheWholeBatchAgainChangesNothing() throws Exception {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor processor = Fixtures.processor(ledger);
        TransactionProcessingService service = new TransactionProcessingService(processor);

        service.run(new InMemoryTransactionSource(briefDataset()));
        BigDecimal before = ledger.balance("ACC-201", "USD");
        int entriesBefore = ledger.entries().size();

        service.run(new InMemoryTransactionSource(briefDataset())); // whole batch replayed

        assertEquals(before, ledger.balance("ACC-201", "USD"));
        assertEquals(entriesBefore, ledger.entries().size());
    }

    @Test
    void garbageRecordsDoNotStopTheBatch() throws Exception {
        List<RawTransaction> batch = List.of(
                Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "10"),
                new RawTransaction(2, null, null, null, null, null, null, null),
                new RawTransaction(3, "TXN\nFORGED", "R", "x", "A", "??", "NaN", "usd"),
                Fixtures.raw(4, "TXN-2", "REQ-2", "2", "ACC-1", "CREDIT", "10"));
        InMemoryLedger ledger = new InMemoryLedger();

        ProcessingReport report = new TransactionProcessingService(Fixtures.processor(ledger))
                .run(new InMemoryTransactionSource(batch));

        assertEquals(2, report.count(TransactionStatus.PROCESSED));
        assertEquals(2, report.count(TransactionStatus.REJECTED));
        assertEquals(new BigDecimal("20"), ledger.balance("ACC-1", "USD"));
        assertTrue(report.toText().contains("TXN?FORGED")); // unsafe characters neutralised in output
    }

    @Test
    void csvSourceParsesRowsAndTurnsMalformedLinesIntoRejectedRecords() throws Exception {
        Path file = Files.createTempFile("txns", ".csv");
        try {
            Files.writeString(file, String.join("\n",
                    "transactionId,requestId,sequence,accountId,transactionType,amount,currency",
                    "TXN-1,REQ-1,1,ACC-1,CREDIT,10,USD",
                    "",
                    "only,three,columns",
                    "TXN-2,REQ-2,3,ACC-1,CREDIT,10,USD") + "\n", StandardCharsets.UTF_8);

            List<RawTransaction> rows = new CsvFileTransactionSource(file).read();

            assertEquals(3, rows.size());
            assertEquals("TXN-1", rows.get(0).transactionId());
            assertTrue(rows.get(1).parseError() != null);
            assertEquals(3, rows.get(2).inputOrder());

            InMemoryLedger ledger = new InMemoryLedger();
            ProcessingReport report = new TransactionProcessingService(Fixtures.processor(ledger))
                    .run(new InMemoryTransactionSource(rows));
            assertEquals(1, report.count(TransactionStatus.PROCESSED));
            assertEquals(1, report.count(TransactionStatus.REJECTED));
            assertEquals(1, report.count(TransactionStatus.PENDING_SEQUENCE)); // seq 2 never arrived
            assertEquals(List.of(2L), report.missingSequences());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void accountIdsAreMaskedForLogging() {
        assertEquals("****201", StructuredLog.maskAccount("ACC-201"));
        assertEquals("***", StructuredLog.maskAccount("A1"));
        assertEquals("***", StructuredLog.maskAccount(null));
    }
}
