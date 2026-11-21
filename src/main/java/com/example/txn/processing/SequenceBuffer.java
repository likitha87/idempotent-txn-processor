package com.example.txn.processing;

import com.example.txn.model.TransactionRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Out-of-order strategy (Option A, sequence-based). Holds early arrivals until every lower sequence
 * number has been resolved, then releases them strictly in order.
 *
 * A sequence is "resolved" when its transaction reached a terminal outcome (PROCESSED / FAILED /
 * REJECTED) or when an invalid record that carried that sequence was rejected (skipped). This keeps one
 * bad record from blocking the stream forever.
 *
 * Not thread-safe by itself; the processor serialises access.
 */
public final class SequenceBuffer {

    public enum OfferResult { BUFFERED, STALE, CONFLICT }

    private static final int MAX_REPORTED_GAPS = 1_000;

    private long nextExpected;
    private final TreeMap<Long, TransactionRecord> pending = new TreeMap<>();
    private final TreeSet<Long> skipped = new TreeSet<>();

    public SequenceBuffer(long firstSequence) {
        this.nextExpected = firstSequence;
    }

    public OfferResult offer(TransactionRecord record) {
        long seq = record.request().sequenceNumber();
        if (seq < nextExpected) {
            return OfferResult.STALE;      // that slot was already consumed
        }
        if (pending.containsKey(seq)) {
            return OfferResult.CONFLICT;   // two different transactions claim the same slot
        }
        skipped.remove(seq);               // a valid record may still claim a slot a bad record only "reserved"
        pending.put(seq, record);
        return OfferResult.BUFFERED;
    }

    /** Marks a slot as resolved without a transaction (invalid input that carried this sequence). */
    public void skip(long seq) {
        if (seq >= nextExpected && !pending.containsKey(seq)) {
            skipped.add(seq);
        }
    }

    /** Removes and returns all records that are now releasable, in sequence order. */
    public List<TransactionRecord> drainReady() {
        List<TransactionRecord> ready = new ArrayList<>();
        while (true) {
            TransactionRecord next = pending.remove(nextExpected);
            if (next != null) {
                ready.add(next);
                nextExpected++;
            } else if (skipped.remove(nextExpected)) {
                nextExpected++;
            } else {
                return ready;
            }
        }
    }

    /** Sequence numbers below the highest buffered one that have not arrived yet (capped). */
    public List<Long> missingSequences() {
        List<Long> missing = new ArrayList<>();
        if (pending.isEmpty()) {
            return missing;
        }
        long highest = pending.lastKey();
        for (long s = nextExpected; s < highest && missing.size() < MAX_REPORTED_GAPS; s++) {
            if (!pending.containsKey(s) && !skipped.contains(s)) {
                missing.add(s);
            }
        }
        return missing;
    }

    public Collection<TransactionRecord> pending() {
        return List.copyOf(pending.values());
    }

    public long nextExpected() {
        return nextExpected;
    }
}
