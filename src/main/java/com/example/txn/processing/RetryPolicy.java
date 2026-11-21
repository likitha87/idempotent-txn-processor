package com.example.txn.processing;

/**
 * Bounded retry with exponential backoff.
 *
 * @param maxAttempts total attempts including the first (so 3 = 1 try + 2 retries)
 */
public record RetryPolicy(int maxAttempts, long initialBackoffMs, long maxBackoffMs) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (initialBackoffMs < 0 || maxBackoffMs < 0) {
            throw new IllegalArgumentException("backoff values must be >= 0");
        }
        if (initialBackoffMs > maxBackoffMs) {
            throw new IllegalArgumentException("initialBackoffMs must be <= maxBackoffMs");
        }
    }

    public static RetryPolicy of(int maxAttempts) {
        return new RetryPolicy(maxAttempts, 100, 2_000);
    }

    /** Delay after a failed attempt, capped without risking long overflow. */
    public long backoffAfterAttempt(int failedAttempt) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("failedAttempt must be >= 1");
        }
        long delay = initialBackoffMs;
        for (int i = 1; i < failedAttempt && delay < maxBackoffMs; i++) {
            // Check before multiplying so a very large retry count cannot overflow.
            if (delay > maxBackoffMs / 2) {
                return maxBackoffMs;
            }
            delay *= 2;
        }
        return Math.min(delay, maxBackoffMs);
    }
}
