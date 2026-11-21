package com.example.txn.processing;

import com.example.txn.ledger.InsufficientFundsException;
import com.example.txn.ledger.Ledger;
import com.example.txn.model.RawTransaction;
import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionStatus;
import com.example.txn.report.ProcessingReport;
import com.example.txn.store.IdempotencyStore;
import com.example.txn.util.StructuredLog;
import com.example.txn.validation.RequestValidator;
import com.example.txn.validation.ValidationResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Core business logic. Knows nothing about where records come from (see TransactionSource).
 *
 * Pipeline per record:
 * <ol>
 *   <li>validate - invalid records are isolated and never stop the batch</li>
 *   <li>claim in the idempotency store - duplicates stop here, whenever they arrive</li>
 *   <li>buffer by sequence number - early arrivals wait for their predecessors</li>
 *   <li>release in order and process with bounded retry; the ledger apply is itself idempotent</li>
 * </ol>
 *
 * {@link #submit} is serialised: ordering is a per-stream property, so one stream = one logical consumer.
 * Scale-out strategy (partition by accountId) is described in docs/DESIGN.md.
 */
public final class TransactionProcessor {

    private final RequestValidator validator;
    private final IdempotencyStore store;
    private final Ledger ledger;
    private final FaultInjector faults;
    private final RetryPolicy retryPolicy;
    private final Sleeper sleeper;
    private final SequenceBuffer buffer;
    private final List<InputEvent> events = new ArrayList<>();

    public TransactionProcessor(RequestValidator validator, IdempotencyStore store, Ledger ledger,
                                FaultInjector faults, RetryPolicy retryPolicy, Sleeper sleeper,
                                long firstSequence) {
        this.validator = validator;
        this.store = store;
        this.ledger = ledger;
        this.faults = faults;
        this.retryPolicy = retryPolicy;
        this.sleeper = sleeper;
        this.buffer = new SequenceBuffer(firstSequence);
    }

    public synchronized void submit(RawTransaction raw) {
        ValidationResult validation = validator.validate(raw);
        if (!validation.isValid()) {
            onInvalid(raw, validation);
            return;
        }
        TransactionRequest request = validation.request().orElseThrow();

        IdempotencyStore.ClaimResult claim = store.claim(request);
        if (!claim.firstSeen()) {
            onDuplicate(raw, request, claim.record());
            return;
        }

        TransactionRecord record = claim.record();
        events.add(new InputEvent(raw.inputOrder(), request.transactionId(), request.requestId(),
                request.sequenceNumber(), InputEvent.Kind.ACCEPTED, "", record));
        StructuredLog.info("RECEIVED", "txnId", request.transactionId(), "requestId", request.requestId(),
                "seq", request.sequenceNumber(), "account", StructuredLog.maskAccount(request.accountId()));

        switch (buffer.offer(record)) {
            case BUFFERED -> record.transitionTo(TransactionStatus.PENDING_SEQUENCE, "waiting for earlier sequence");
            case STALE -> reject(record, "sequence slot already consumed");
            case CONFLICT -> reject(record, "sequence already held by another transaction");
        }
        drain();
    }

    private void onInvalid(RawTransaction raw, ValidationResult validation) {
        String detail = String.join("; ", validation.errors());
        Long seq = validation.sequence().isPresent() ? validation.sequence().getAsLong() : null;
        events.add(new InputEvent(raw.inputOrder(), sanitize(raw.transactionId()), sanitize(raw.requestId()),
                seq, InputEvent.Kind.INVALID, detail, null));
        StructuredLog.warn("REJECTED_INVALID", "input", raw.inputOrder(), "txnId", sanitize(raw.transactionId()),
                "errors", "[" + detail + "]");
        // The slot is resolved without a transaction, otherwise every later sequence would wait forever.
        validation.sequence().ifPresent(buffer::skip);
        drain();
    }

    private void onDuplicate(RawTransaction raw, TransactionRequest request, TransactionRecord original) {
        original.addDuplicateRequestId(request.requestId());
        boolean payloadDiffers = !original.request().fingerprint().equals(request.fingerprint());
        String detail = "duplicate of transaction currently " + original.status()
                + (payloadDiffers ? "; WARNING: payload differs from original (ignored)" : "");
        events.add(new InputEvent(raw.inputOrder(), request.transactionId(), request.requestId(),
                request.sequenceNumber(), InputEvent.Kind.DUPLICATE, detail, original));
        StructuredLog.info("DUPLICATE_IGNORED", "txnId", request.transactionId(), "requestId", request.requestId(),
                "originalStatus", original.status(), "payloadDiffers", payloadDiffers);
    }

    private void reject(TransactionRecord record, String reason) {
        record.transitionTo(TransactionStatus.REJECTED, reason);
        StructuredLog.warn("REJECTED_SEQUENCE", "txnId", record.request().transactionId(), "reason", reason);
    }

    private void drain() {
        for (TransactionRecord ready : buffer.drainReady()) {
            process(ready);
        }
    }

    private void process(TransactionRecord record) {
        TransactionRequest request = record.request();
        String txnId = request.transactionId();

        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            record.incrementAttempts();
            record.transitionTo(TransactionStatus.PROCESSING, "attempt " + attempt);
            try {
                faults.check(FaultInjector.Point.BEFORE_COMMIT, txnId, attempt);
                ledger.apply(request);                       // idempotent + atomic
                faults.check(FaultInjector.Point.AFTER_COMMIT, txnId, attempt);

                record.transitionTo(TransactionStatus.PROCESSED, attempt == 1 ? "" : "succeeded on attempt " + attempt);
                StructuredLog.info("PROCESSED", "txnId", txnId, "attempt", attempt);
                return;
            } catch (InsufficientFundsException e) {
                // permanent business failure: retrying cannot help
                record.transitionTo(TransactionStatus.FAILED, "business rule: " + e.getMessage() + " (not retried)");
                StructuredLog.warn("FAILED_BUSINESS_RULE", "txnId", txnId, "attempt", attempt);
                return;
            } catch (TransientProcessingException e) {
                if (attempt < retryPolicy.maxAttempts()) {
                    long backoff = retryPolicy.backoffAfterAttempt(attempt);
                    record.transitionTo(TransactionStatus.RETRY_PENDING,
                            "attempt " + attempt + " failed transiently; retrying");
                    StructuredLog.warn("RETRY_SCHEDULED", "txnId", txnId, "attempt", attempt, "backoffMs", backoff);
                    sleeper.sleep(backoff);
                    // Do not continue retrying after the worker has been asked to stop.
                    // Reconcile first because the previous attempt may have committed before
                    // its acknowledgement was interrupted/lost.
                    if (Thread.currentThread().isInterrupted()) {
                        failOrReconcile(record, "retry interrupted; processing stopped");
                        return;
                    }
                } else {
                    failOrReconcile(record, "retries exhausted after " + attempt + " attempts");
                    return;
                }
            } catch (RuntimeException e) {
                // safe error handling: record the type only, never the message (may contain customer data)
                failOrReconcile(record, "unexpected error: " + e.getClass().getSimpleName());
                return;
            }
        }
    }

    /**
     * The ledger is the source of truth for "did this take effect?". If the effect was committed but the
     * acknowledgement kept failing, the state store is brought in line instead of reporting a false FAILED.
     */
    private void failOrReconcile(TransactionRecord record, String reason) {
        String txnId = record.request().transactionId();
        if (ledger.find(txnId).isPresent()) {
            record.transitionTo(TransactionStatus.PROCESSED, "reconciled: ledger effect was committed (" + reason + ")");
            StructuredLog.warn("RECONCILED_PROCESSED", "txnId", txnId);
        } else {
            record.transitionTo(TransactionStatus.FAILED, reason);
            StructuredLog.warn("FAILED", "txnId", txnId, "reason", reason);
        }
    }

    public synchronized ProcessingReport report() {
        return ProcessingReport.build(List.copyOf(events), buffer.missingSequences(), buffer.nextExpected(), ledger);
    }

    /** Strips anything outside the ID alphabet so untrusted values cannot forge log lines. */
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_-]", "?");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
