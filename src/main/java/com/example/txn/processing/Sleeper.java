package com.example.txn.processing;

/** Injectable sleep so tests run instantly. */
@FunctionalInterface
public interface Sleeper {
    void sleep(long millis);

    Sleeper REAL = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    Sleeper NO_OP = millis -> { };
}
