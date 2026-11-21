package com.example.txn.ledger;

import com.example.txn.model.TransactionRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The business side effect: balance update + ledger entry. This is the source of truth for
 * "has this transaction taken effect?".
 */
public interface Ledger {

    /**
     * Posts the transaction atomically (balance and ledger entry change together or not at all).
     * Idempotent: applying the same transactionId again returns the original entry and changes nothing.
     */
    LedgerEntry apply(TransactionRequest request) throws InsufficientFundsException;

    Optional<LedgerEntry> find(String transactionId);

    BigDecimal balance(String accountId, String currency);

    /** Balances keyed by "account currency", sorted for stable reporting. */
    Map<String, BigDecimal> balances();

    List<LedgerEntry> entries();
}
