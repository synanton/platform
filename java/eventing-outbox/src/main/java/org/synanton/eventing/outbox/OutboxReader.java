package org.synanton.eventing.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(OutboxReader.class);

    private final EventOutbox outbox;
    private final OutboxDispatcher dispatcher;
    private final int batchSize;
    private final OutboxMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;

    public OutboxReader(EventOutbox outbox, OutboxDispatcher dispatcher, int batchSize) {
        this(outbox, dispatcher, batchSize, new OutboxMetrics());
    }

    public OutboxReader(EventOutbox outbox, OutboxDispatcher dispatcher, int batchSize, OutboxMetrics metrics) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
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
        metrics.attempt(entry.attempts() + 1);
        try {
            dispatcher.dispatch(entry);
            boolean marked = outbox.markDispatched(entry.eventId());
            if (marked) {
                metrics.dispatched();
            }
            return marked;
        } catch (RuntimeException failure) {
            outbox.markFailed(entry.eventId(), failure.getMessage());
            metrics.failed();
            log.warn("outbox dispatch failed event_id={} attempt={} error={}",
                    entry.eventId(), entry.attempts() + 1, failure.getClass().getSimpleName());
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
