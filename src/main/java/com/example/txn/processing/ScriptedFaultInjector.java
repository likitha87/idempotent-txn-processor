package com.example.txn.processing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Fails a given transaction at a given point a fixed number of times, then succeeds. */
public final class ScriptedFaultInjector implements FaultInjector {

    private final Map<String, AtomicInteger> remaining = new ConcurrentHashMap<>();

    public ScriptedFaultInjector failTimes(String transactionId, Point point, int times) {
        remaining.put(key(transactionId, point), new AtomicInteger(times));
        return this;
    }

    @Override
    public void check(Point point, String transactionId, int attempt) {
        AtomicInteger left = remaining.get(key(transactionId, point));
        if (left != null && left.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new TransientProcessingException("simulated transient failure at " + point);
        }
    }

    private static String key(String transactionId, Point point) {
        return transactionId + "|" + point;
    }
}
