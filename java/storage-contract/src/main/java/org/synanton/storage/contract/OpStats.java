package org.synanton.storage.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-operation latency statistics with a bounded reservoir (last 1024 samples)
 * for p50/p95/p99 (§8.3 latency distributions). Thread-safe.
 */
public final class OpStats {

    private static final int RESERVOIR = 1024;

    private long count;
    private long errors;
    private long sumNanos;
    private long maxNanos;
    private final List<Long> reservoir = new ArrayList<>();

    public synchronized void record(long latencyNanos, boolean success) {
        count++;
        if (!success) {
            errors++;
        }
        sumNanos += latencyNanos;
        maxNanos = Math.max(maxNanos, latencyNanos);
        reservoir.add(latencyNanos);
        if (reservoir.size() > RESERVOIR) {
            reservoir.remove(reservoir.size() - RESERVOIR - 1);
        }
    }

    public synchronized long count() {
        return count;
    }

    public synchronized long errors() {
        return errors;
    }

    public synchronized double avgMs() {
        return count == 0 ? 0.0 : (sumNanos / 1_000_000.0) / count;
    }

    public synchronized double maxMs() {
        return maxNanos / 1_000_000.0;
    }

    public synchronized double p50Ms() {
        return percentileMs(50);
    }

    public synchronized double p95Ms() {
        return percentileMs(95);
    }

    public synchronized double p99Ms() {
        return percentileMs(99);
    }

    private double percentileMs(int p) {
        if (reservoir.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = new ArrayList<>(reservoir);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * p / 100.0))) / 1_000_000.0;
    }
}
