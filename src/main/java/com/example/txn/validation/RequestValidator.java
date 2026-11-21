package com.example.txn.validation;

import com.example.txn.model.RawTransaction;
import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/** Validates untrusted input. Pure function: no I/O, no state. */
public final class RequestValidator {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final long MAX_SEQUENCE = 1_000_000_000L;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");

    public ValidationResult validate(RawTransaction raw) {
        List<String> errors = new ArrayList<>();

        if (raw.parseError() != null) {
            errors.add("record could not be parsed: " + raw.parseError());
        }
        requireId("transactionId", raw.transactionId(), errors);
        requireId("requestId", raw.requestId(), errors);
        requireId("accountId", raw.accountId(), errors);

        OptionalLong sequence = parseSequence(raw.sequence(), errors);
        TransactionType type = parseType(raw.transactionType(), errors);
        BigDecimal amount = parseAmount(raw.amount(), errors);
        String currency = raw.currency();
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            errors.add("currency must be a 3-letter uppercase code");
        }

        String reversalOf = raw.reversalOfTransactionId();
        if (type == TransactionType.REVERSAL) {
            requireId("reversalOfTransactionId", reversalOf, errors);
            if (raw.transactionId() != null && raw.transactionId().equals(reversalOf)) {
                errors.add("a transaction cannot reverse itself");
            }
        } else if (reversalOf != null && !reversalOf.isBlank()) {
            errors.add("reversalOfTransactionId is allowed only for REVERSAL");
        }

        if (!errors.isEmpty()) {
            return new ValidationResult(Optional.empty(), List.copyOf(errors), sequence);
        }
        TransactionRequest request = new TransactionRequest(
                raw.transactionId(), raw.requestId(), sequence.getAsLong(),
                raw.accountId(), type, amount, currency,
                type == TransactionType.REVERSAL ? reversalOf : null);
        return new ValidationResult(Optional.of(request), List.of(), sequence);
    }

    private static void requireId(String field, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(field + " is required");
        } else if (!ID.matcher(value).matches()) {
            errors.add(field + " has invalid format (allowed: letters, digits, '-', '_', max 64)");
        }
    }

    private static OptionalLong parseSequence(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add("sequence is required");
            return OptionalLong.empty();
        }
        try {
            long seq = Long.parseLong(value.trim());
            if (seq < 1 || seq > MAX_SEQUENCE) {
                errors.add("sequence out of allowed range");
                return OptionalLong.empty();
            }
            return OptionalLong.of(seq);
        } catch (NumberFormatException e) {
            errors.add("sequence must be a whole number");
            return OptionalLong.empty();
        }
    }

    private static TransactionType parseType(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add("transactionType is required");
            return null;
        }
        try {
            return TransactionType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            errors.add("transactionType is not supported");
            return null;
        }
    }

    private static BigDecimal parseAmount(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add("amount is required");
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(value.trim());
            if (amount.signum() <= 0) {
                errors.add("amount must be greater than zero");
                return null;
            }
            if (amount.stripTrailingZeros().scale() > 2) {
                errors.add("amount supports at most 2 decimal places");
                return null;
            }
            if (amount.compareTo(MAX_AMOUNT) > 0) {
                errors.add("amount exceeds maximum allowed");
                return null;
            }
            return amount;
        } catch (NumberFormatException e) {
            errors.add("amount must be numeric");
            return null;
        }
    }
}
