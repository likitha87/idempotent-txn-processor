package com.example.txn.ledger;

import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Thread-safe in-memory ledger. Duplicate check, validation and posting are one atomic operation. */
public final class InMemoryLedger implements Ledger {
    private final Map<String, LedgerEntry> entriesByTxn = new LinkedHashMap<>();
    private final Map<String, BigDecimal> balances = new HashMap<>();
    private final Map<String, String> reversalByOriginalTxn = new HashMap<>();

    @Override
    public synchronized LedgerEntry apply(TransactionRequest r) throws InsufficientFundsException {
        LedgerEntry duplicate = entriesByTxn.get(r.transactionId());
        if (duplicate != null) return duplicate; // idempotent delivery replay

        TransactionType postedType = r.transactionType();
        String originalId = r.reversalOfTransactionId();
        BigDecimal signedDelta;
        if (postedType == TransactionType.REVERSAL) {
            LedgerEntry original = originalId == null ? null : entriesByTxn.get(originalId);
            if (original == null) throw new InsufficientFundsException("original transaction not found");
            if (original.type() == TransactionType.REVERSAL) throw new InsufficientFundsException("reversals cannot be reversed");
            if (!original.accountId().equals(r.accountId()) || !original.currency().equals(r.currency())
                    || original.amount().compareTo(r.amount()) != 0) {
                throw new InsufficientFundsException("reversal details do not match original transaction");
            }
            if (reversalByOriginalTxn.containsKey(originalId)) {
                throw new InsufficientFundsException("original transaction has already been reversed");
            }
            signedDelta = original.type() == TransactionType.CREDIT ? r.amount().negate() : r.amount();
        } else {
            signedDelta = postedType == TransactionType.CREDIT ? r.amount() : r.amount().negate();
        }

        String accountKey = key(r.accountId(), r.currency());
        BigDecimal updated = balances.getOrDefault(accountKey, BigDecimal.ZERO).add(signedDelta);
        if (updated.signum() < 0) throw new InsufficientFundsException("insufficient funds for debit or reversal");

        LedgerEntry entry = new LedgerEntry(r.transactionId(), r.accountId(), postedType, r.amount(),
                r.currency(), updated, Instant.now(), originalId);
        balances.put(accountKey, updated);
        entriesByTxn.put(r.transactionId(), entry);
        if (postedType == TransactionType.REVERSAL) reversalByOriginalTxn.put(originalId, r.transactionId());
        return entry;
    }

    @Override public synchronized Optional<LedgerEntry> find(String id) { return Optional.ofNullable(entriesByTxn.get(id)); }
    @Override public synchronized BigDecimal balance(String account, String currency) { return balances.getOrDefault(key(account, currency), BigDecimal.ZERO); }
    @Override public synchronized Map<String, BigDecimal> balances() { return new TreeMap<>(balances); }
    @Override public synchronized List<LedgerEntry> entries() { return new ArrayList<>(entriesByTxn.values()); }
    private static String key(String account, String currency) { return account + " " + currency; }
}
