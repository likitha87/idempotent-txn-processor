package com.example.txn.validation;

import com.example.txn.model.TransactionRequest;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * @param request  present only when the record is valid
 * @param errors   field-level messages; never echo raw input values (safe error handling / no log injection)
 * @param sequence the sequence number if it could be parsed, even for an invalid record, so the ordering
 *                 buffer can step over it instead of stalling forever
 */
public record ValidationResult(Optional<TransactionRequest> request, List<String> errors, OptionalLong sequence) {

    public boolean isValid() {
        return request.isPresent();
    }
}
