package com.example.txn;

import com.example.txn.model.RawTransaction;
import com.example.txn.validation.RequestValidator;
import com.example.txn.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorTest {

    private final RequestValidator validator = new RequestValidator();

    @Test
    void validRecordIsAccepted() {
        ValidationResult r = validator.validate(Fixtures.raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100.50"));
        assertTrue(r.isValid());
        assertEquals(1L, r.request().orElseThrow().sequenceNumber());
    }

    @Test
    void negativeAmountIsRejectedButSequenceIsStillExposed() {
        ValidationResult r = validator.validate(Fixtures.raw(1, "TXN-4", "REQ-4", "4", "ACC-2", "DEBIT", "-10"));
        assertFalse(r.isValid());
        assertTrue(r.errors().contains("amount must be greater than zero"));
        assertEquals(4L, r.sequence().getAsLong()); // lets the ordering buffer step over the bad record
    }

    @Test
    void zeroAmountIsRejected() {
        assertFalse(validator.validate(Fixtures.raw(1, "T", "R", "1", "A", "CREDIT", "0")).isValid());
    }

    @Test
    void missingMandatoryFieldsAreRejected() {
        RawTransaction empty = new RawTransaction(1, null, null, null, null, null, null, null);
        ValidationResult r = validator.validate(empty);
        assertFalse(r.isValid());
        assertTrue(r.errors().size() >= 6);
        assertFalse(r.sequence().isPresent());
    }

    @Test
    void unsupportedTypeBadCurrencyAndTooManyDecimalsAreRejected() {
        assertFalse(validator.validate(Fixtures.raw(1, "T", "R", "1", "A", "REFUND", "10")).isValid());
        assertFalse(validator.validate(new RawTransaction(1, "T", "R", "1", "A", "CREDIT", "10", "usd")).isValid());
        assertFalse(validator.validate(Fixtures.raw(1, "T", "R", "1", "A", "CREDIT", "10.005")).isValid());
        assertFalse(validator.validate(Fixtures.raw(1, "T", "R", "1", "A", "CREDIT", "abc")).isValid());
        assertFalse(validator.validate(Fixtures.raw(1, "T", "R", "0", "A", "CREDIT", "10")).isValid());
        assertFalse(validator.validate(Fixtures.raw(1, "T", "R", "x", "A", "CREDIT", "10")).isValid());
    }

    @Test
    void identifiersWithUnsafeCharactersAreRejected() {
        assertFalse(validator.validate(Fixtures.raw(1, "TXN\nevent=FORGED", "R", "1", "A", "CREDIT", "10")).isValid());
    }

    @Test
    void errorMessagesNeverEchoRawInput() {
        ValidationResult r = validator.validate(Fixtures.raw(1, "T", "R", "1", "A", "CREDIT", "secret-4111111111111111"));
        assertFalse(r.isValid());
        assertFalse(String.join(" ", r.errors()).contains("4111"));
    }
}
