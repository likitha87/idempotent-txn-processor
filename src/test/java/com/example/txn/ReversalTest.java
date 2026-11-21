package com.example.txn;

import com.example.txn.ledger.InMemoryLedger;
import com.example.txn.model.RawTransaction;
import com.example.txn.processing.Sleeper;
import com.example.txn.processing.TransactionProcessor;
import com.example.txn.report.ProcessingReport;
import com.example.txn.source.InMemoryTransactionSource;
import com.example.txn.model.TransactionStatus;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReversalTest {
    @Test
    void reversalPostsInverseEffectExactlyOnce() throws Exception {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor processor = Fixtures.processor(ledger, com.example.txn.processing.FaultInjector.none(), 3, Sleeper.NO_OP);
        RawTransaction credit = Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100");
        RawTransaction reversal = new RawTransaction(2, "TXN-2", "REQ-2", "2", "ACC-1", "REVERSAL", "100", "USD", "TXN-1", null);
        RawTransaction duplicateReversal = new RawTransaction(3, "TXN-2", "REQ-2-RETRY", "2", "ACC-1", "REVERSAL", "100", "USD", "TXN-1", null);
        ProcessingReport report = new com.example.txn.TransactionProcessingService(processor)
                .run(new InMemoryTransactionSource(List.of(credit, reversal, duplicateReversal)));
        assertEquals(2, report.count(TransactionStatus.PROCESSED));
        assertEquals(1, report.count(TransactionStatus.DUPLICATE));
        assertEquals(new BigDecimal("0"), ledger.balance("ACC-1", "USD"));
        assertEquals(2, ledger.entries().size());
        assertEquals("TXN-1", ledger.find("TXN-2").orElseThrow().reversalOfTransactionId());
    }

    @Test
    void secondDistinctReversalOfSameOriginalIsRejectedWithoutEffect() throws Exception {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor processor = Fixtures.processor(ledger);
        RawTransaction credit = Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100");
        RawTransaction reversal1 = new RawTransaction(2, "TXN-2", "REQ-2", "2", "ACC-1", "REVERSAL", "100", "USD", "TXN-1", null);
        RawTransaction reversal2 = new RawTransaction(3, "TXN-3", "REQ-3", "3", "ACC-1", "REVERSAL", "100", "USD", "TXN-1", null);
        ProcessingReport report = new com.example.txn.TransactionProcessingService(processor)
                .run(new InMemoryTransactionSource(List.of(credit, reversal1, reversal2)));
        assertEquals(2, report.count(TransactionStatus.PROCESSED));
        assertEquals(1, report.count(TransactionStatus.FAILED));
        assertEquals(BigDecimal.ZERO, ledger.balance("ACC-1", "USD"));
        assertEquals(2, ledger.entries().size());
    }

    @Test
    void reversalMustReferenceAnOriginalTransaction() {
        RawTransaction invalid = new RawTransaction(1, "TXN-1", "REQ-1", "1", "ACC-1", "REVERSAL", "10", "USD");
        assertFalse(new com.example.txn.validation.RequestValidator().validate(invalid).isValid());
    }
}
