package com.example.txn.util;

import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Minimal structured (key=value) logging on java.util.logging, so the project has zero runtime
 * dependencies. Callers pass IDs only: never amounts, names or raw input. Account IDs are masked.
 */
public final class StructuredLog {

    private static final Logger LOG = Logger.getLogger("txn");

    private StructuredLog() { }

    public static void info(String event, Object... kv) {
        LOG.log(Level.INFO, render(event, kv));
    }

    public static void warn(String event, Object... kv) {
        LOG.log(Level.WARNING, render(event, kv));
    }

    private static String render(String event, Object[] kv) {
        StringBuilder sb = new StringBuilder("event=").append(event);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(' ').append(kv[i]).append('=').append(kv[i + 1]);
        }
        return sb.toString();
    }

    /** Keeps only the last 3 characters: ACC-201 -> ****201 */
    public static String maskAccount(String accountId) {
        if (accountId == null || accountId.length() <= 3) {
            return "***";
        }
        return "****" + accountId.substring(accountId.length() - 3);
    }

    /** One line per event: {@code 2026-11-20T12:00:00Z level=INFO event=...}. Sent to stdout-friendly stderr. */
    public static void configureOneLineFormat() {
        Logger root = LogManager.getLogManager().getLogger("");
        for (Handler h : root.getHandlers()) {
            h.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord r) {
                    return r.getInstant() + " level=" + r.getLevel() + " " + formatMessage(r) + System.lineSeparator();
                }
            });
        }
    }
}
