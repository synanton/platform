package org.synanton.eventing.outbox;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Post-commit worker that reads pending outbox rows and dispatches them, marking
 * each row dispatched only after broker acknowledgement. A failed dispatch leaves
 * the row pending (attempts/last_error updated) so a later drain retries it — this
 * is the restart-recovery path: killing and restarting the process drains the same
 * rows again under at-least-once semantics.
 */
public final class OutboxReader implements AutoCloseable {

    private final EventOutbox outbox;
    private final OutboxDispatcher dispatcher;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;

    public OutboxReader(EventOutbox outbox, OutboxDispatcher dispatcher, int batchSize) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    /** Starts the scheduled reader loop. Idempotent; a second start is a no-op. */
    public void start(long interval, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        if (interval <= 0) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (running.compareAndSet(false, true)) {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "outbox-reader");
                thread.setDaemon(true);
                return thread;
            });
            scheduler = executor;
            executor.scheduleWithFixedDelay(this::drain, 0, interval, unit);
        }
    }

    /** One synchronous drain pass; returns the number of rows dispatched this pass. */
    public int drain() {
        int dispatched = 0;
        for (OutboxEntry entry : outbox.pending(batchSize)) {
            if (dispatchOne(entry)) {
                dispatched++;
            }
        }
        return dispatched;
    }

    private boolean dispatchOne(OutboxEntry entry) {
        try {
            dispatcher.dispatch(entry);
            return outbox.markDispatched(entry.eventId());
        } catch (RuntimeException failure) {
            outbox.markFailed(entry.eventId(), failure.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        running.set(false);
        ScheduledExecutorService executor = scheduler;
        scheduler = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
