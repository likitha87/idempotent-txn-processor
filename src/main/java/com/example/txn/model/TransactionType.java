package com.example.txn.model;

/** Supported ledger operations. REVERSAL must reference an earlier original transaction. */
public enum TransactionType { CREDIT, DEBIT, REVERSAL }
