package com.example.txn.processing;

/** A failure that may succeed if retried (timeout, connection reset, lock timeout ...). */
public class TransientProcessingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TransientProcessingException(String message) {
        super(message);
    }
}
