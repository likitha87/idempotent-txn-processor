-- PostgreSQL production schema reference. Execute migrations through Flyway/Liquibase.
-- Monetary values use NUMERIC (never FLOAT/DOUBLE). All timestamps are UTC timestamptz.

CREATE TABLE account_balance (
    account_id       varchar(64) NOT NULL,
    currency         char(3) NOT NULL,
    available_amount numeric(19,2) NOT NULL DEFAULT 0 CHECK (available_amount >= 0),
    version          bigint NOT NULL DEFAULT 0,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, currency)
);

CREATE TABLE transaction_request (
    transaction_id   varchar(64) PRIMARY KEY,
    request_id       varchar(64) NOT NULL,
    account_id       varchar(64) NOT NULL,
    sequence_number  bigint NOT NULL CHECK (sequence_number > 0),
    transaction_type varchar(16) NOT NULL CHECK (transaction_type IN ('CREDIT','DEBIT','REVERSAL')),
    amount           numeric(19,2) NOT NULL CHECK (amount > 0),
    currency         char(3) NOT NULL,
    reversal_of_transaction_id varchar(64) NULL REFERENCES transaction_request(transaction_id),
    payload_hash     char(64) NOT NULL,
    status           varchar(24) NOT NULL CHECK (status IN
                       ('RECEIVED','PENDING_SEQUENCE','PROCESSING','RETRY_PENDING','PROCESSED','FAILED','REJECTED')),
    attempt_count    integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NULL,
    last_error_code varchar(64) NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CHECK ((transaction_type = 'REVERSAL' AND reversal_of_transaction_id IS NOT NULL)
        OR (transaction_type <> 'REVERSAL' AND reversal_of_transaction_id IS NULL))
);

CREATE INDEX ix_transaction_status_retry ON transaction_request(status, next_attempt_at)
    WHERE status IN ('RECEIVED','RETRY_PENDING');
CREATE INDEX ix_transaction_account_sequence ON transaction_request(account_id, currency, sequence_number);
CREATE UNIQUE INDEX uq_one_reversal_per_original ON transaction_request(reversal_of_transaction_id)
    WHERE transaction_type = 'REVERSAL';

CREATE TABLE ledger_entry (
    ledger_entry_id  bigserial PRIMARY KEY,
    transaction_id   varchar(64) NOT NULL UNIQUE REFERENCES transaction_request(transaction_id),
    account_id       varchar(64) NOT NULL,
    currency         char(3) NOT NULL,
    entry_type       varchar(16) NOT NULL CHECK (entry_type IN ('CREDIT','DEBIT','REVERSAL')),
    amount           numeric(19,2) NOT NULL CHECK (amount > 0),
    signed_amount    numeric(19,2) NOT NULL,
    reversal_of_transaction_id varchar(64) NULL REFERENCES ledger_entry(transaction_id),
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ledger_account_created ON ledger_entry(account_id, currency, created_at);

CREATE TABLE transaction_outbox (
    outbox_id        bigserial PRIMARY KEY,
    aggregate_id     varchar(64) NOT NULL,
    event_type       varchar(64) NOT NULL,
    payload          jsonb NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    published_at     timestamptz NULL,
    publish_attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX ix_outbox_unpublished ON transaction_outbox(outbox_id) WHERE published_at IS NULL;

-- Posting transaction (all steps in ONE DB transaction):
-- 1) INSERT transaction_request ... ON CONFLICT (transaction_id) DO NOTHING.
--    If conflict, compare payload_hash; same hash => duplicate, different hash => idempotency conflict.
-- 2) Lock account row: SELECT ... FROM account_balance WHERE account_id=? AND currency=? FOR UPDATE.
-- 3) Validate sequence policy and business rules. For reversal, lock/check original ledger row and ensure
--    no prior reversal (unique partial index is final race-condition guard).
-- 4) INSERT ledger_entry (unique transaction_id); UPDATE account_balance; UPDATE transaction_request status.
-- 5) INSERT outbox event in the same transaction. Commit once; never split balance and ledger writes.
-- Retry transient SQLSTATEs with bounded exponential backoff + jitter. Do not retry validation/business failures.
-- Consumers of outbox events must also deduplicate by event/transaction id.
