# Idempotent Background Transaction Processing Service

A lightweight Java 17 service that processes transaction requests **idempotently**, tolerates **duplicates and
out-of-order arrival**, **retries transient failures safely**, and reports clear **status and metrics**.
Built for the FDE L2 practical assessment (Epic: Idempotent Background Transaction Processing Service).

- Zero runtime dependencies (JDK only). JUnit 5 for tests.
- Full design rationale: [`docs/DESIGN.md`](docs/DESIGN.md)
- PostgreSQL production schema and transaction boundaries: [`docs/DB_SCHEMA_POSTGRESQL.sql`](docs/DB_SCHEMA_POSTGRESQL.sql)
- Likely follow-up questions with crisp answers: [`docs/INTERVIEW_PREP.md`](docs/INTERVIEW_PREP.md)

## Requirements

JDK 17+ and Maven 3.8+.

## Run

```bash
# build + run all tests
mvn clean test

# run the sample scenario (sample/transactions.csv, TXN-2005 fails once after commit, then succeeds on retry)
mvn -q -DskipTests package
java -cp target/classes com.example.txn.App

# options
java -cp target/classes com.example.txn.App \
  --input sample/transactions.csv \
  --max-attempts 3 \
  --inject TXN-2005:AFTER_COMMIT:1          # TXN-ID:BEFORE_COMMIT|AFTER_COMMIT:times
```

Try these to see the failure paths:

```bash
# retries exhausted before anything is committed -> FAILED, no ledger effect
java -cp target/classes com.example.txn.App --inject TXN-2005:BEFORE_COMMIT:99

# ack lost on every attempt, but effect was committed -> reconciled to PROCESSED (credited exactly once)
java -cp target/classes com.example.txn.App --inject TXN-2005:AFTER_COMMIT:99
```

## Sample input

`sample/transactions.csv` (the dataset from the brief, with a currency column added):

```
transactionId,requestId,sequence,accountId,transactionType,amount,currency[,reversalOfTransactionId]
TXN-2002,REQ-2,2,ACC-201,DEBIT,40,USD
TXN-2001,REQ-1,1,ACC-201,CREDIT,100,USD
TXN-2002,REQ-2-DUP,2,ACC-201,DEBIT,40,USD
TXN-2003,REQ-3,3,ACC-201,CREDIT,25,USD
TXN-2004,REQ-4,4,ACC-202,DEBIT,-10,USD
TXN-2005,REQ-5,5,ACC-203,CREDIT,500,USD
```

## Sample output

Full output (logs + report) is in [`sample/sample-output.txt`](sample/sample-output.txt). Summary:

| # | Txn | Outcome | Why |
|---|-----|---------|-----|
| 1 | TXN-2002 | PROCESSED | Arrived early, buffered as `PENDING_SEQUENCE`, released after seq 1 |
| 2 | TXN-2001 | PROCESSED | Releases itself and TXN-2002, in order |
| 3 | TXN-2002 (REQ-2-DUP) | DUPLICATE | Already processed; no second ledger entry |
| 4 | TXN-2003 | PROCESSED | |
| 5 | TXN-2004 | REJECTED | Invalid amount (-10); isolated, sequence 4 stepped over |
| 6 | TXN-2005 | PROCESSED (2 attempts) | Ack lost after commit; retry was a no-op on the ledger |

Final balances: `ACC-201 = 85` (100 - 40 + 25), `ACC-203 = 500` (credited **once**, not twice).

## Project layout

```
src/main/java/com/example/txn
  App.java                         CLI entry point
  TransactionProcessingService     the "background job": source -> processor -> report
  model/        RawTransaction (untrusted) -> TransactionRequest (validated), TransactionRecord (state), enums
  source/       TransactionSource interface + InMemory / CSV adapters   (swap for Kafka/SQS/DB later)
  validation/   RequestValidator (pure, field-level errors, no raw-value echo)
  store/        IdempotencyStore interface + in-memory impl (atomic claim by transactionId)
  ledger/       Ledger interface + in-memory impl (atomic, idempotent balance + entry)
  processing/   TransactionProcessor (core), SequenceBuffer, RetryPolicy, FaultInjector(s)
  report/       ProcessingReport (counts by status, audit rows, gaps, balances)
  util/         StructuredLog (key=value logs, account masking)
src/test/java/com/example/txn      44 tests: validator, idempotency, ordering, retry/resilience, retry policy, reversals, end-to-end
```

## Status lifecycle

```
RECEIVED -> PENDING_SEQUENCE -> PROCESSING -> PROCESSED
                                   |  ^
                                   v  |
                             RETRY_PENDING -> FAILED
RECEIVED -> REJECTED            (stale / conflicting sequence)
Input-level outcomes also reported: DUPLICATE, REJECTED (invalid input)
```

## Test coverage (maps to L2-FR-12 and the acceptance criteria)

| Scenario | Where |
|----------|-------|
| Duplicate after processed / late in batch / while still buffered / different payload / concurrent claims | `IdempotencyTest` |
| Out of order: reversed batch, debit before credit, gaps, late arrival, stale and conflicting sequence | `OrderingTest` |
| Invalid records isolated, do not stall sequence | `OrderingTest`, `EndToEndTest`, `ValidatorTest` |
| Retry then success, lost-ack no double effect, exhaustion -> FAILED, reconcile, no retry on business failure, backoff | `RetryResilienceTest` |
| Brief's six-row dataset, whole-batch replay is a no-op, summary reconciles with input count | `EndToEndTest` |

## Key assumptions (details and trade-offs in `docs/DESIGN.md`)

- `sequenceNumber` is a **single global stream sequence** starting at 1 (matches the brief's dataset).
- `transactionId` is the idempotency key. A repeated transactionId is a duplicate regardless of requestId.
- Terminal outcomes (PROCESSED / FAILED / REJECTED) all consume their sequence slot so one bad record cannot block the stream.
- The executable assessment adapter is in-memory; a PostgreSQL production schema, constraints, indexes and atomic posting workflow are documented in `docs/DB_SCHEMA_POSTGRESQL.sql`.
