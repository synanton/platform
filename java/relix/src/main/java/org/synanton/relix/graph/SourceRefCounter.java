package org.synanton.relix.graph;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SourceRefCounter {

    public record Result(int remaining, boolean detached) {}

    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    public void set(String entityId, int count) {
        counts.put(entityId, new AtomicInteger(count));
    }

    public Result decrement(String entityId) {
        AtomicInteger counter = counts.computeIfAbsent(entityId, ignored -> new AtomicInteger(1));
        int remaining;
        do {
            remaining = counter.get();
            if (remaining <= 0) {
                return new Result(0, true);
            }
        } while (!counter.compareAndSet(remaining, remaining - 1));
        int next = remaining - 1;
        return new Result(next, next == 0);
    }
}
