package com.example.txn.source;

import com.example.txn.model.RawTransaction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a simple CSV (no quoted commas). Header:
 * {@code transactionId,requestId,sequence,accountId,transactionType,amount,currency[,reversalOfTransactionId]}.
 * Lines with the wrong column count become records carrying a parseError, so they are rejected
 * individually instead of failing the whole file.
 */
public final class CsvFileTransactionSource implements TransactionSource {
    private static final int REQUIRED_COLUMNS = 7;
    private final Path file;

    public CsvFileTransactionSource(Path file) {
        this.file = file;
    }

    @Override
    public List<RawTransaction> read() throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<RawTransaction> out = new ArrayList<>();
        int order = 0;
        for (int i = 1; i < lines.size(); i++) { // skip header
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            order++;
            String[] f = line.split(",", -1);
            if (f.length < REQUIRED_COLUMNS || f.length > REQUIRED_COLUMNS + 1) {
                out.add(new RawTransaction(order, null, null, null, null, null, null, null,
                        null, "expected 7 or 8 columns"));
                continue;
            }
            out.add(new RawTransaction(order, f[0].trim(), f[1].trim(), f[2].trim(),
                    f[3].trim(), f[4].trim(), f[5].trim(), f[6].trim(),
                    f.length == 8 && !f[7].isBlank() ? f[7].trim() : null));
        }
        return out;
    }
}
