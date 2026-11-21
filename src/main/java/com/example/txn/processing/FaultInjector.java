package com.example.txn.processing;

/**
 * Test seam that simulates transient infrastructure failures (L2-FR-08).
 * <ul>
 *   <li>BEFORE_COMMIT: failure before the ledger is touched (e.g. gateway timeout). Nothing committed.</li>
 *   <li>AFTER_COMMIT: ledger committed but the acknowledgement was lost. This is the dangerous case:
 *       the retry MUST NOT apply the effect twice.</li>
 * </ul>
 */
public interface FaultInjector {

    enum Point { BEFORE_COMMIT, AFTER_COMMIT }

    /** Throws {@link TransientProcessingException} if a fault is scripted for this point. */
    void check(Point point, String transactionId, int attempt);

    static FaultInjector none() {
        return (point, transactionId, attempt) -> { };
    }
}
