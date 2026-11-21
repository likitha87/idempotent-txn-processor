package com.example.txn.ledger;

/** Permanent ledger/business-rule failure. Never retried: retrying cannot change the outcome. */
public class InsufficientFundsException extends Exception {
    private static final long serialVersionUID = 1L;

    public InsufficientFundsException(String message) {
        super(message);
    }
}
