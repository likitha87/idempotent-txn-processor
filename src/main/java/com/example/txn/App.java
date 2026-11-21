package com.example.txn;

import com.example.txn.ledger.InMemoryLedger;
import com.example.txn.processing.FaultInjector;
import com.example.txn.processing.RetryPolicy;
import com.example.txn.processing.ScriptedFaultInjector;
import com.example.txn.processing.Sleeper;
import com.example.txn.processing.TransactionProcessor;
import com.example.txn.report.ProcessingReport;
import com.example.txn.source.CsvFileTransactionSource;
import com.example.txn.store.InMemoryIdempotencyStore;
import com.example.txn.util.StructuredLog;
import com.example.txn.validation.RequestValidator;

import java.nio.file.Path;

/**
 * CLI entry point.
 *
 * <pre>
 * java -cp target/classes com.example.txn.App [--input file.csv] [--max-attempts 3]
 *                                             [--inject TXN-ID:BEFORE_COMMIT|AFTER_COMMIT:times]...
 * </pre>
 * Defaults reproduce the assessment scenario: sample/transactions.csv, 3 attempts, and TXN-2005 failing
 * once after its ledger commit (the lost-acknowledgement case).
 */
public final class App {

    public static void main(String[] args) throws Exception {
        Path input = Path.of("sample/transactions.csv");
        int maxAttempts = 3;
        ScriptedFaultInjector injector = new ScriptedFaultInjector();
        boolean customInjection = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input" -> input = Path.of(args[++i]);
                case "--max-attempts" -> maxAttempts = Integer.parseInt(args[++i]);
                case "--inject" -> {
                    String[] p = args[++i].split(":");
                    injector.failTimes(p[0], FaultInjector.Point.valueOf(p[1]), Integer.parseInt(p[2]));
                    customInjection = true;
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        if (!customInjection) {
            injector.failTimes("TXN-2005", FaultInjector.Point.AFTER_COMMIT, 1);
        }

        StructuredLog.configureOneLineFormat();
        TransactionProcessor processor = new TransactionProcessor(
                new RequestValidator(), new InMemoryIdempotencyStore(), new InMemoryLedger(),
                injector, RetryPolicy.of(maxAttempts), Sleeper.REAL, 1);

        ProcessingReport report = new TransactionProcessingService(processor)
                .run(new CsvFileTransactionSource(input));
        System.out.println();
        System.out.println(report.toText());
    }

    private App() { }
}
