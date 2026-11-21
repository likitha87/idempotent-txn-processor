package com.example.txn;

import com.example.txn.processing.RetryPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryPolicyTest {

    @Test
    void exponentialBackoffIsCappedWithoutOverflow() {
        RetryPolicy policy = new RetryPolicy(10, 100, 2_000);
        assertEquals(100, policy.backoffAfterAttempt(1));
        assertEquals(200, policy.backoffAfterAttempt(2));
        assertEquals(400, policy.backoffAfterAttempt(3));
        assertEquals(2_000, policy.backoffAfterAttempt(Integer.MAX_VALUE));
    }

    @Test
    void rejectsInvalidRetryConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 100, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(3, -1, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(3, 100, -1));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(3, 500, 100));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(3, 100, 200)
                .backoffAfterAttempt(0));
    }
}
