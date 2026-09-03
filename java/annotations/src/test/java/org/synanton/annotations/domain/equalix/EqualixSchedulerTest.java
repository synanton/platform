package org.synanton.annotations.domain.equalix;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.synanton.annotations.domain.ProcessingRunService;
import org.synanton.annotations.domain.model.ProcessingRun;
import org.synanton.annotations.domain.repository.ProcessingRunRepository;
import org.synanton.annotations.domain.resolutor.RecalculationPlan;
import org.synanton.annotations.domain.resolutor.RecalculationWorkItem;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EqualixSchedulerTest {

    private final InMemoryProcessingRunRepository runRepository = new InMemoryProcessingRunRepository();
    private final ProcessingRunService processingRuns = new ProcessingRunService(
            runRepository, Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC));

    private EqualixScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.close();
        }
    }

    @Test
    void shouldServiceInteractiveWorkBeforeQueuedHistoricalBacklog() throws InterruptedException {
        List<String> executionOrder = new CopyOnWriteArrayList<>();
        int historicalCount = 20;
        CountDownLatch latch = new CountDownLatch(historicalCount + 1);
        RecalculationExecutor recordingExecutor = (item, tenantId, runId) -> {
            executionOrder.add(item.definitionId());
            latch.countDown();
        };
        scheduler = new EqualixScheduler(1, 20, processingRuns, recordingExecutor);

        // Queue a large HISTORICAL_RECALC backlog first, then one INTERACTIVE item - all
        // while the scheduler is not yet consuming, so priority (not submission order)
        // decides who is dequeued first once start() is called.
        for (int i = 0; i < historicalCount; i++) {
            scheduler.submit(planFor("hist-" + i), WorkloadClass.HISTORICAL_RECALC, "test", "1.0", "demo");
        }
        scheduler.submit(planFor("interactive-1"), WorkloadClass.INTERACTIVE, "test", "1.0", "demo");

        scheduler.start();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executionOrder.getFirst()).isEqualTo("interactive-1");
    }

    @Test
    void shouldCreateATraceableProcessingRunPerWorkItem() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        RecalculationExecutor executor = (item, tenantId, runId) -> latch.countDown();
        scheduler = new EqualixScheduler(1, 20, processingRuns, executor);

        scheduler.submit(planFor("payment"), WorkloadClass.USER_TRIGGERED_RECALC, "equalix-test", "9.9", "demo");
        scheduler.start();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runRepository.all()).hasSize(1);
        ProcessingRun run = runRepository.all().getFirst();
        assertThat(run.producer()).isEqualTo("equalix-test");
        assertThat(run.producerVersion()).isEqualTo("9.9");
        assertThat(run.scope()).contains("evaluation_run:chunk:t1");
        assertThat(run.status()).isEqualTo(ProcessingRun.SUCCEEDED);
    }

    @Test
    void shouldMarkProcessingRunFailedWhenExecutorThrows() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        RecalculationExecutor failingExecutor = (item, tenantId, runId) -> {
            latch.countDown();
            throw new RuntimeException("boom");
        };
        scheduler = new EqualixScheduler(1, 20, processingRuns, failingExecutor);

        scheduler.submit(planFor("payment"), WorkloadClass.USER_TRIGGERED_RECALC, "equalix-test", "1.0", "demo");
        scheduler.start();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // Give the worker a moment to record the failure after the executor throws.
        Thread.sleep(100);
        assertThat(runRepository.all()).hasSize(1);
        assertThat(runRepository.all().getFirst().status()).isEqualTo(ProcessingRun.FAILED);
        assertThat(runRepository.all().getFirst().errorSummary()).isEqualTo("boom");
    }

    private static RecalculationPlan planFor(String definitionId) {
        return new RecalculationPlan(
                List.of(new RecalculationWorkItem("chunk", "t1", definitionId, null, null)),
                EnumSet.noneOf(org.synanton.annotations.domain.resolutor.Projection.class));
    }

    private static class InMemoryProcessingRunRepository implements ProcessingRunRepository {
        private final Map<UUID, ProcessingRun> stored = new ConcurrentHashMap<>();

        List<ProcessingRun> all() {
            return List.copyOf(stored.values());
        }

        @Override
        public ProcessingRun insert(ProcessingRun run) {
            stored.put(run.processingRunId(), run);
            return run;
        }

        @Override
        public Optional<ProcessingRun> findById(UUID processingRunId) {
            return Optional.ofNullable(stored.get(processingRunId));
        }

        @Override
        public void complete(UUID processingRunId, String status, Instant endedAt, String errorSummary, String resourceConsumptionJson) {
            ProcessingRun current = stored.get(processingRunId);
            stored.put(processingRunId, new ProcessingRun(
                    current.processingRunId(), current.producer(), current.producerVersion(), current.tenantId(),
                    current.definitionId(), current.definitionVersion(), current.scope(), current.startedAt(),
                    endedAt, status, errorSummary, resourceConsumptionJson));
        }
    }
}
