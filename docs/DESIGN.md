# Design Note

## 1. Problem in one paragraph

Requests arrive duplicated, out of order, and with transient failures in between. The service must apply each
business transaction **exactly once in effect**, in a **deterministic order**, and always be able to say what
happened to every input record.

## 2. Architecture

```
 TransactionSource ──► RequestValidator ──► IdempotencyStore.claim ──► SequenceBuffer ──► process() ──► Ledger
 (CSV / list /           (reject bad,         (atomic by txnId;          (hold early         (bounded retry,    (atomic,
  later: queue)           keep sequence)       duplicates stop here)      arrivals)           reconcile)         idempotent)
                                                                                                  │
                                                                      ProcessingReport ◄──────────┘ (InputEvent audit trail)
```

- **Input handling is separated from business logic** (`TransactionSource` vs `TransactionProcessor`). A queue
  consumer would call `processor.submit(...)` from its listener; nothing else changes.
- **Infrastructure behind interfaces**: `IdempotencyStore`, `Ledger`, `FaultInjector`, `Sleeper`. Business rules
  (ordering, retry decisions, state transitions) live in `TransactionProcessor` / `SequenceBuffer`.
- **Untrusted vs trusted types**: `RawTransaction` (strings) becomes `TransactionRequest` only via the validator.

## 3. Status lifecycle

| Status | Meaning |
|--------|---------|
| RECEIVED | Accepted past validation and dedup, not yet sequenced |
| PENDING_SEQUENCE | Waiting for earlier sequence numbers |
| PROCESSING | Being applied (attempt n) |
| RETRY_PENDING | Transient failure, backing off before next attempt |
| PROCESSED | Effect committed and acknowledged |
| FAILED | Business-rule failure (e.g. insufficient funds) or retries exhausted with no effect |
| DUPLICATE | Input record repeated an already-seen transactionId |
| REJECTED | Invalid input, or sequence slot stale/conflicting |

Every input line produces exactly one audit row (`InputEvent`), so the summary counts always add up to the input size.

## 4. Idempotency design

Two independent guards, so a bug in one cannot cause a double effect:

1. **Gate (state store)**: `IdempotencyStore.claim(request)` is atomic (`putIfAbsent`). Exactly one caller per
   `transactionId` wins; everyone else gets the existing record and is classed DUPLICATE.
2. **Effect (ledger)**: `Ledger.apply` is itself idempotent. It checks `transactionId` inside the same critical
   section that updates the balance and appends the entry, and returns the original entry on replay.

Why `transactionId` and not `requestId`: `requestId` identifies one *delivery attempt*; it legitimately changes
on every client retry (`REQ-2` vs `REQ-2-DUP`). The business identity is `transactionId`. All duplicate
requestIds seen are kept on the record for traceability.

Same transactionId with a *different payload* is still ignored (the first wins) but flagged with a WARNING in
the report/log: that is a client bug worth alerting on, not something to silently apply.

## 5. Duplicate detection

Claim happens **at ingestion, before sequencing**. That means a duplicate is caught whether the original is
already processed, failed, or still waiting in the buffer, and regardless of where it falls in the batch (L2-FR-06).
Whole-batch replay is therefore a no-op (covered by a test).

## 6. Out-of-order strategy: Option A (sequence-based)

`SequenceBuffer` holds early arrivals in a sorted map and releases them strictly in order from `nextExpected`.

- Future sequence → `PENDING_SEQUENCE`; released when all lower slots are resolved.
- **Slots resolve on any terminal outcome** (PROCESSED, FAILED, REJECTED) or when an *invalid* record that carried
  that sequence is rejected (the slot is "skipped"). This is what lets TXN-2005 (seq 5) proceed past the invalid
  TXN-2004 (seq 4) in the brief's dataset.
- A valid record may still claim a slot that an invalid record only reserved, so a malformed record carrying a
  wrong sequence cannot poison a real one (while that slot is still ahead of the cursor).
- Sequence below the cursor for a *new* transaction → REJECTED (stale). Two different transactions for the same
  pending slot → second REJECTED (conflict).
- Missing sequences are reported (`Missing sequences` in the summary; capped at 1,000 entries).

**Why this matters for the domain**: in the dataset the DEBIT (seq 2) arrives before the CREDIT (seq 1) that
funds it. Processing in arrival order would fail it for insufficient funds. Sequencing is what makes the outcome
deterministic.

**Trade-offs**
- Head-of-line blocking: one missing sequence holds everything behind it. Mitigation in production: a gap
  timeout that escalates (alert, then skip or park), and per-account sequences so one account's gap does not
  block others.
- A FAILED record consumes its slot, so later records proceed. Chosen because blocking the entire stream on one
  failure is worse for customers; the failure is visible and recoverable (see section 7).
- Option B/C were not chosen: balance updates are order-sensitive (a debit needs prior credits), so "latest wins"
  (C) is not valid for a ledger, and a per-transaction state machine (B) does not order *across* transactions.

## 7. Retry and failure handling

- `RetryPolicy(maxAttempts, initialBackoff, maxBackoff)`: exponential backoff, no sleep after the last attempt.
- Only `TransientProcessingException` is retried. `InsufficientFundsException` (permanent business rule) fails
  immediately with 1 attempt. Unknown exceptions fail safely (type name recorded, message never recorded) and
  are not blindly retried. If the worker thread is interrupted during backoff, processing stops, the interrupt
  flag is preserved, and the ledger is checked before choosing FAILED vs PROCESSED.
- **Two simulated fault points** (`FaultInjector`):
  - `BEFORE_COMMIT`: nothing committed; retry is trivially safe. Exhaustion ends in FAILED with no effect.
  - `AFTER_COMMIT` (lost acknowledgement): the ledger *did* commit. The retry calls `Ledger.apply` again, which
    returns the original entry, so there is **no second effect**. This is the case that proves idempotency.
- **Reconcile before failing**: when retries are exhausted, the processor asks the ledger (source of truth)
  whether the effect exists. If yes the state is corrected to PROCESSED ("reconciled"); only if not is it FAILED.
  This avoids the worst outcome: state says FAILED but money has moved.

**Recovering FAILED records**: a FAILED transaction's transactionId stays claimed, so a naive resubmission is a
DUPLICATE (tested). Recovery is meant to be an explicit, audited replay path (operator/job that re-drives
records in FAILED), not silent resubmission. That path is a documented next step, not implemented here.

## 8. Data consistency

- `InMemoryLedger.apply` computes the new balance and validates funds **before** mutating anything, then updates
  balance and appends the entry in one synchronized step. A rejected debit leaves no partial state.
- Production equivalent: one DB transaction containing the ledger insert (UNIQUE `transaction_id`) and the
  balance update with a row lock on the account.
- Remaining non-atomic seam: the state store and ledger are separate. The reconcile rule above is the
  compensation for a crash/ack-loss between the two; in production the state change would join the same DB
  transaction or use an outbox.

## 9. Observability

- **Structured logs** (`key=value`): `RECEIVED`, `PROCESSED`, `DUPLICATE_IGNORED`, `REJECTED_INVALID`,
  `REJECTED_SEQUENCE`, `RETRY_SCHEDULED`, `FAILED`, `FAILED_BUSINESS_RULE`, `RECONCILED_PROCESSED`,
  `BATCH_STARTED/FINISHED`. Each carries txnId/requestId/attempt.
- **Processing report**: counts by status, retries performed, next expected sequence, missing sequences, ledger
  entry count, per-record audit table (order, status, attempts, detail), final balances.
- Production: export the same counters as metrics (processed/duplicate/failed/retried totals, buffer depth,
  oldest pending age, gap age) with alerts on FAILED > 0, buffer depth, and gap age.

## 10. Security and data handling

- Validation of every field, strict ID alphabet (`[A-Za-z0-9_-]{1,64}`) which also blocks **log injection**.
- Error messages name the field, never echo the offending value.
- Logs contain IDs only: no amounts, account IDs are masked (`****201`). Unsafe characters in rejected IDs are
  neutralised in the report.
- No secrets, no hardcoded credentials, no external calls.
- Exceptions: only the class name is stored, never the message (may contain customer data).

## 11. Assumptions and known limitations (stated honestly)

1. `sequenceNumber` is one global stream sequence starting at 1.
2. Single logical consumer: `submit` is synchronized because ordering is a per-stream property. The store and
   ledger are independently thread-safe and covered by concurrency tests.
3. Runtime persistence is in-memory only: state is lost on restart. `TransactionSource`, `IdempotencyStore`, and
   `Ledger` are adapter seams; the PostgreSQL schema and atomic write protocol are specified separately.
4. No gap timeout: a missing sequence leaves later records `PENDING_SEQUENCE` indefinitely (reported, not hidden).
5. Buffer is unbounded. Production needs a cap with back-pressure.
6. REVERSAL is implemented in the in-memory ledger and references the original transactionId. Production must
   enforce the same one-reversal rule with the partial unique index in `DB_SCHEMA_POSTGRESQL.sql`.
7. No automatic replay of FAILED records (see section 7).

## 12. Production hardening roadmap

| Area | Recommendation |
|------|----------------|
| Persistence | Postgres: `transactions` (PK transaction_id), `ledger_entries` (UNIQUE transaction_id), `accounts` (row lock). Claim via `INSERT ... ON CONFLICT DO NOTHING`. |
| Queue semantics | At-least-once delivery (Kafka/SQS) is *why* idempotency matters. Ack only after commit. Partition by accountId to keep per-account order and scale horizontally. DLQ for FAILED and poison messages. |
| Ordering | Per-account sequences, gap timeout with escalation, bounded buffer with back-pressure. |
| Concurrency | Optimistic locking or `SELECT ... FOR UPDATE` per account; one active consumer per partition. |
| Retries | Add jitter, retry budget/circuit breaker for the downstream, separate delayed-retry queue instead of blocking sleep. |
| Recovery | Admin replay endpoint/job for FAILED, reconciliation job comparing state store vs ledger, outbox for downstream events. |
| Monitoring | Metrics + dashboards + alerts (see section 9), trace IDs propagated from upstream. |
| Security | AuthN/Z on any ingestion API, encryption at rest for PII, retention policy on audit rows, secrets from a vault. |
| Testing | Add property-based tests (random permutations + duplicates must converge to the same final state), and a load test. |

## 13. Database design and transaction boundaries

The runnable assessment version uses in-memory adapters, but the production PostgreSQL schema and posting
transaction are provided in [`DB_SCHEMA_POSTGRESQL.sql`](DB_SCHEMA_POSTGRESQL.sql). The schema separates
request/idempotency state, account balances, immutable ledger entries, and an outbox. It uses primary/unique
constraints as the final concurrency guard, `NUMERIC(19,2)` for money, foreign keys for reversals, a partial
unique index to permit at most one reversal per original transaction, and indexes for retry polling and
account history. The account row is locked with `SELECT ... FOR UPDATE`; the request status, ledger insert,
balance update, and outbox insert commit in one database transaction. A retry after an uncertain commit first
checks the unique transaction id and ledger effect rather than applying the amount again.

## 14. Reversal semantics

`REVERSAL` is implemented as a new transaction referencing `reversalOfTransactionId`. It must match the
original account, currency, and amount; it cannot reverse another reversal; and one original transaction can
be reversed only once. Reversing a CREDIT debits the account; reversing a DEBIT credits it. A reversal that
would make the balance negative fails without changing the ledger. The reversal itself has its own unique
transaction id and is idempotent on replay.

## 15. AI-assisted development

- **AI tools used**: Update this line before submission with the exact tools actually used (for example, ChatGPT),
  and accurately describe which parts they assisted with. Do not claim use of a tool that was not used.
- **Where it helped**: speed on the repetitive parts (model, interfaces, test fixtures), and prompting for edge cases
  I might otherwise have missed (lost-ack after commit, stale/conflicting sequences, log injection).
- **How the output was validated, not blindly accepted**:
  - Read and reasoned through every class; the design decisions (global sequence, slot-consumption rule,
    reconcile-before-fail, two-guard idempotency) are mine to explain and defend.
  - Wrote the tests to *assert behaviour the brief demands* (balances, attempt counts, no double effect),
    not just to exercise code; 44 tests covering validation, idempotency, ordering, retries, reversals and end-to-end behavior.
  - Ran the brief's exact dataset end to end and checked balances by hand (ACC-201 = 100 - 40 + 25 = 85).
  - Deliberately probed failure paths via CLI fault injection (`--inject ...`) and confirmed outcomes.
- **What I changed or rejected**: kept it dependency-free instead of pulling in a framework; chose
  sequence-based ordering over "latest wins" because ledger operations are order-sensitive.
