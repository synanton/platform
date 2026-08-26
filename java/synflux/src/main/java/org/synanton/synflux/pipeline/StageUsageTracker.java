package org.synanton.synflux.pipeline;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.function.Supplier;

public final class StageUsageTracker {

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();

    private StageUsageTracker() {
    }

    public record TimedResult<T>(T value, long wallMs, long cpuNs) {}

    public static long currentCpuNanos() {
        return THREAD_MX.isCurrentThreadCpuTimeSupported()
            ? THREAD_MX.getCurrentThreadCpuTime() : 0L;
    }

    public static <T> TimedResult<T> time(Supplier<T> work) {
        long wallStart = System.nanoTime();
        long cpuStart = currentCpuNanos();
        T value = work.get();
        long wallMs = (System.nanoTime() - wallStart) / 1_000_000L;
        long cpuNs = currentCpuNanos() - cpuStart;
        return new TimedResult<>(value, wallMs, cpuNs);
    }

    public static void timeVoid(Runnable work, TimedCallback callback) {
        long wallStart = System.nanoTime();
        long cpuStart = currentCpuNanos();
        work.run();
        long wallMs = (System.nanoTime() - wallStart) / 1_000_000L;
        long cpuNs = currentCpuNanos() - cpuStart;
        callback.onComplete(wallMs, cpuNs);
    }

    @FunctionalInterface
    public interface TimedCallback {
        void onComplete(long wallMs, long cpuNs);
    }
}
