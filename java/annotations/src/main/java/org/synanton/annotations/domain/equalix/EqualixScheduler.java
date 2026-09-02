package org.synanton.annotations.domain.equalix;

import org.synanton.annotations.domain.ProcessingRunService;
import org.synanton.annotations.domain.model.ProcessingRun;
import org.synanton.annotations.domain.resolutor.RecalculationPlan;
import org.synanton.annotations.domain.resolutor.RecalculationWorkItem;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Equalix: priority/concurrency-controlled execution of recalculation plans (design §50,
 * §62). Work items are always dequeued in {@link WorkloadClass#priority()} order
 * regardless of submission order, so a large {@code HISTORICAL_RECALC}/{@code
 * ANALYTICS_REBUILD} backlog can never delay a queued {@code INTERACTIVE}/{@code
 * INCREMENTAL_INGESTION} item behind it (Invariant 12).
 *
 * <p>Package-local name only - see the INDEX.md Open Questions entry on whether this
 * should eventually share an implementation with the GPU track's reserved
 * {@code EqualixScheduler} name (README GPU-5). Not resolved here.
 */
public class EqualixScheduler implements AutoCloseable {

    private final PriorityBlockingQueue<ScheduledWorkItem> queue = new PriorityBlockingQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private final int poolSize;
    private final long pollTimeoutMs;
    private final ProcessingRunService processingRuns;
    private final RecalculationExecutor executor;

    private ExecutorService workers;
    private volatile boolean running;

    public EqualixScheduler(int poolSize, long pollTimeoutMs, ProcessingRunService processingRuns, RecalculationExecutor executor) {
        this.poolSize = poolSize;
        this.pollTimeoutMs = pollTimeoutMs;
        this.processingRuns = processingRuns;
        this.executor = executor;
    }

    /** Enqueues every work item of {@code plan} under {@code workloadClass}. Queue order is priority-based, not FIFO. */
    public void submit(RecalculationPlan plan, WorkloadClass workloadClass, String producer, String producerVersion, String tenantId) {
        for (RecalculationWorkItem item : plan.workItems()) {
            queue.add(new ScheduledWorkItem(item, workloadClass, producer, producerVersion, tenantId, sequence.incrementAndGet()));
        }
    }

    /** Starts the bounded worker pool. Safe to call once; a second call is a no-op. */
    public synchronized void start() {
        if (workers != null) {
            return;
        }
        running = true;
        workers = Executors.newFixedThreadPool(poolSize);
        for (int i = 0; i < poolSize; i++) {
            workers.submit(this::workerLoop);
        }
    }

    public int queueSize() {
        return queue.size();
    }

    private void workerLoop() {
        while (running) {
            ScheduledWorkItem scheduled;
            try {
                scheduled = queue.poll(pollTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (scheduled == null) {
                continue;
            }
            runOne(scheduled);
        }
    }

    private void runOne(ScheduledWorkItem scheduled) {
        RecalculationWorkItem item = scheduled.item();
        // design §12: every executed work item is backed by a traceable processing run.
        ProcessingRun run = processingRuns.start(
                scheduled.producer(), scheduled.producerVersion(), scheduled.tenantId(),
                item.definitionId(), item.toVersion(),
                "evaluation_run:" + item.targetType() + ":" + item.targetId());
        try {
            executor.execute(item, scheduled.tenantId(), run.processingRunId());
            processingRuns.complete(run.processingRunId(), ProcessingRun.SUCCEEDED, null, null);
        } catch (RuntimeException e) {
            processingRuns.complete(run.processingRunId(), ProcessingRun.FAILED, e.getMessage(), null);
        }
    }

    @Override
    public void close() {
        running = false;
        if (workers != null) {
            workers.shutdownNow();
            try {
                workers.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record ScheduledWorkItem(
            RecalculationWorkItem item,
            WorkloadClass workloadClass,
            String producer,
            String producerVersion,
            String tenantId,
            long sequence
    ) implements Comparable<ScheduledWorkItem> {
        @Override
        public int compareTo(ScheduledWorkItem other) {
            int byPriority = Integer.compare(this.workloadClass.priority(), other.workloadClass.priority());
            return byPriority != 0 ? byPriority : Long.compare(this.sequence, other.sequence);
        }
    }
}
