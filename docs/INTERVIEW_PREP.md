# Interview Prep: likely follow-ups (short answers)

## 30-second pitch
Requests can be duplicated, reordered, and retried. I gate on `transactionId` (atomic claim), order by
sequence (buffer early arrivals), apply via an idempotent atomic ledger, and retry only transient failures with
backoff. If an ack is lost after commit, the retry is a no-op and, on exhaustion, I reconcile against the ledger
instead of reporting a false FAILED.

## Core concepts (note-friendly)
- **Idempotency** = same request N times -> same final state as once.
- **Key** = business `transactionId` (not `requestId`, which changes per retry).
- **Two guards**: state-store claim + idempotent ledger apply.
- **Ordering** = sequence buffer; slot freed on any terminal outcome or skipped invalid record.
- **Retry** = transient only, bounded, exponential backoff; permanent errors never retried.
- **Lost-ack** = effect committed, ack failed -> retry must be harmless -> reconcile before FAILED.
- **Source of truth** = ledger, state store is derived.

## Likely questions

**1. How do you guarantee exactly-once?**
Not true exactly-once delivery; it's at-least-once delivery + idempotent processing = exactly-once *effect*.
Atomic claim by transactionId plus an idempotent ledger apply.

**2. Why transactionId and not requestId as the key?**
requestId identifies a delivery attempt and changes on retry (`REQ-2` vs `REQ-2-DUP`). Using it would let a
retried transaction apply twice.

**3. Same transactionId, different amount?**
First one wins, the later one is DUPLICATE and flagged "payload differs". Silently applying it would corrupt
the ledger; alerting surfaces the client bug.

**4. What if two threads/consumers process the same transaction at once?**
`putIfAbsent` gives exactly one winner (tested with 32 threads). The ledger re-checks inside its critical
section as a second guard. In a DB: UNIQUE constraint + `ON CONFLICT DO NOTHING`.

**5. Why sequence-based ordering and not latest-state?**
Ledger operations are order-sensitive: a debit needs the earlier credit. In the dataset the debit (seq 2) arrives
before the credit (seq 1); arrival order would fail it. Latest-state is for overwrite-style data, not additive
balances.

**6. What are the downsides of your ordering approach?**
Head-of-line blocking if a sequence never arrives, and an unbounded buffer. Fixes: gap timeout with escalation,
per-account sequences, bounded buffer with back-pressure.

**7. What happens to an invalid record's sequence number?**
It is skipped so later records are not blocked. Trade-off: a malformed record with a wrong sequence could skip
a real slot; mitigated because a valid record can still claim a slot not yet passed by the cursor.

**8. Why does FAILED consume its sequence slot?**
Blocking the whole stream on one failure hurts every other customer. The failure stays visible and replayable.

**9. What is the worst failure case and how do you handle it?**
Ledger committed, then the ack/state update fails. A naive retry would double-apply. Mine: retry hits the
idempotent ledger (no-op); if retries exhaust, I check the ledger and set PROCESSED ("reconciled") rather than FAILED.

**10. Which errors do you retry?**
Only transient ones (timeouts, connection resets). Insufficient funds is permanent: 1 attempt, FAILED.
Unknown exceptions fail safely without blind retry.

**11. How do you recover FAILED transactions?**
Resubmission is treated as DUPLICATE by design. Recovery should be an explicit audited replay (admin job)
that re-drives FAILED records, plus a reconciliation job. Documented as next step.

**12. How would this run in production?**
Kafka/SQS consumer calling `submit`; ack only after commit; partition by accountId for per-account ordering and
horizontal scale; Postgres for state and ledger; DLQ; metrics and alerts.

**13. Where would you add transactions/atomicity in production?**
One DB transaction covering ledger insert (UNIQUE transaction_id), balance update (row lock), and state change;
outbox table for downstream events.

**14. How is data protected?**
Strict validation, strict ID alphabet (stops log injection), no amounts in logs, masked account IDs, errors never
echo input, exception messages never stored.

**15. How would you test it more?**
Property-based test: random shuffles + injected duplicates of the same batch must always converge to identical
balances and entry set. Plus a concurrency/load test.

**16. How did AI help and how did you validate it?**
Speed on boilerplate/tests and edge-case prompting. Validated by reviewing every class, writing behavioural
tests tied to the brief's acceptance criteria, running the brief's dataset and hand-checking balances, and
fault injection through the CLI.

**17. What would you do with more time?**
Persistence (Postgres), gap timeout and per-account ordering, durable FAILED replay path, multi-node DB integration, production metrics and alerts
endpoint, property-based tests.

## Numbers to remember
- Brief dataset result: PROCESSED 4, DUPLICATE 1, REJECTED 1, retries 1.
- Balances: ACC-201 = 85, ACC-203 = 500 (not 1000).
- 44 tests; zero runtime dependencies.
