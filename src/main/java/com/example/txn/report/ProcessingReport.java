package com.example.txn.report;

import com.example.txn.ledger.Ledger;
import com.example.txn.model.TransactionStatus;
import com.example.txn.processing.InputEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Processing summary (L2-FR-11): counts by status, per-input audit rows, gaps, retries, balances. */
public final class ProcessingReport {

    public record Row(int inputOrder, String transactionId, String requestId, Long sequence,
                      TransactionStatus status, int attempts, String detail) { }

    private final List<Row> rows;
    private final Map<TransactionStatus, Long> countsByStatus;
    private final int retriesPerformed;
    private final List<Long> missingSequences;
    private final long nextExpectedSequence;
    private final Map<String, BigDecimal> balances;
    private final int ledgerEntries;

    private ProcessingReport(List<Row> rows, Map<TransactionStatus, Long> counts, int retries,
                             List<Long> missing, long nextExpected, Map<String, BigDecimal> balances, int ledgerEntries) {
        this.rows = rows;
        this.countsByStatus = counts;
        this.retriesPerformed = retries;
        this.missingSequences = missing;
        this.nextExpectedSequence = nextExpected;
        this.balances = balances;
        this.ledgerEntries = ledgerEntries;
    }

    public static ProcessingReport build(List<InputEvent> events, List<Long> missing, long nextExpected, Ledger ledger) {
        List<Row> rows = new ArrayList<>();
        Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus s : TransactionStatus.values()) {
            counts.put(s, 0L);
        }
        int retries = 0;
        for (InputEvent e : events) {
            TransactionStatus status;
            int attempts = 0;
            String detail = e.detail();
            switch (e.kind()) {
                case ACCEPTED -> {
                    status = e.record().status();
                    attempts = e.record().attempts();
                    detail = e.record().detail();
                    retries += Math.max(0, attempts - 1);
                }
                case DUPLICATE -> status = TransactionStatus.DUPLICATE;
                default -> status = TransactionStatus.REJECTED;
            }
            counts.merge(status, 1L, Long::sum);
            rows.add(new Row(e.inputOrder(), e.transactionId(), e.requestId(), e.sequence(), status, attempts, detail));
        }
        return new ProcessingReport(List.copyOf(rows), counts, retries, List.copyOf(missing), nextExpected,
                ledger.balances(), ledger.entries().size());
    }

    public List<Row> rows() { return rows; }
    public Map<TransactionStatus, Long> countsByStatus() { return countsByStatus; }
    public long count(TransactionStatus status) { return countsByStatus.get(status); }
    public int totalInputs() { return rows.size(); }
    public int retriesPerformed() { return retriesPerformed; }
    public List<Long> missingSequences() { return missingSequences; }
    public Map<String, BigDecimal> balances() { return balances; }
    public int ledgerEntries() { return ledgerEntries; }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("==================== PROCESSING SUMMARY ====================\n");
        sb.append(String.format("Total input records : %d%n", totalInputs()));
        for (Map.Entry<TransactionStatus, Long> e : countsByStatus.entrySet()) {
            sb.append(String.format("  %-17s : %d%n", e.getKey(), e.getValue()));
        }
        sb.append(String.format("Retries performed   : %d%n", retriesPerformed));
        sb.append(String.format("Next expected seq   : %d%n", nextExpectedSequence));
        sb.append(String.format("Missing sequences   : %s%n", missingSequences.isEmpty() ? "none" : missingSequences));
        sb.append(String.format("Ledger entries      : %d%n", ledgerEntries));

        sb.append("\n-------------------- PER-RECORD AUDIT --------------------\n");
        sb.append(String.format("%-3s %-9s %-11s %-4s %-16s %-8s %s%n",
                "#", "TXN", "REQUEST", "SEQ", "STATUS", "ATTEMPTS", "DETAIL"));
        for (Row r : rows) {
            sb.append(String.format("%-3d %-9s %-11s %-4s %-16s %-8d %s%n",
                    r.inputOrder(), r.transactionId(), r.requestId(),
                    r.sequence() == null ? "-" : r.sequence().toString(),
                    r.status(), r.attempts(), r.detail()));
        }

        sb.append("\n-------------------- FINAL BALANCES ----------------------\n");
        if (balances.isEmpty()) {
            sb.append("(none)\n");
        }
        balances.forEach((k, v) -> sb.append(String.format("%-16s %s%n", k, v.toPlainString())));
        return sb.toString();
    }
}
