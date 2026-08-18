package org.synanton.gateway.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.gateway.client.PlannerClient;
import org.synanton.gateway.client.RelixClient;
import org.synanton.gateway.client.SynquestClient;
import org.synanton.gateway.config.GatewayProperties;
import org.synanton.gateway.domain.GraphResult;
import org.synanton.gateway.domain.Hit;
import org.synanton.gateway.domain.StepOutcome;
import org.synanton.gateway.domain.StepTrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    private final SynquestClient synquestClient;
    private final RelixClient relixClient;
    private final FusionEngine fusionEngine;
    private final ExecutorService executor;
    private final GatewayProperties props;

    public PlanExecutor(
            SynquestClient synquestClient,
            RelixClient relixClient,
            FusionEngine fusionEngine,
            ExecutorService executor,
            GatewayProperties props
    ) {
        this.synquestClient = synquestClient;
        this.relixClient = relixClient;
        this.fusionEngine = fusionEngine;
        this.executor = executor;
        this.props = props;
    }

    public ExecutionResult execute(
            PlannerClient.PlannerResponse plan,
            String tenant,
            String query,
            int topK,
            Map<String, Object> slots
    ) {
        String templateId = plan.templateId() != null ? plan.templateId() : "T3";
        long globalStart = System.currentTimeMillis();

        return switch (templateId) {
            case "T1" -> executeGraphOnly(tenant, query, slots, topK, globalStart);
            case "T2" -> executeHybrid(tenant, query, slots, topK, globalStart);
            default  -> executeSearchOnly(tenant, query, slots, topK, globalStart);
        };
    }

    private ExecutionResult executeHybrid(
            String tenant, String query, Map<String, Object> slots, int topK, long globalStart
    ) {
        List<StepTrace> traces = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        long stepTimeoutMs = props.stepTimeoutMs();

        Future<SynquestResult> synquestFuture = executor.submit(
                searchCallable(tenant, query, topK, stepTimeoutMs, globalStart)
        );
        Future<RelixResult> relixFuture = executor.submit(
                graphCallable(tenant, query, slots, stepTimeoutMs, globalStart)
        );

        SynquestResult sqResult = resolve(synquestFuture, stepTimeoutMs);
        RelixResult rxResult = resolve(relixFuture, stepTimeoutMs);

        traces.add(sqResult.trace());
        traces.add(rxResult.trace());

        if (sqResult.trace().outcome() == StepOutcome.FAILED || sqResult.trace().outcome() == StepOutcome.TIMEOUT) {
            warnings.add("synquest_step_" + sqResult.trace().outcome().name().toLowerCase());
        }
        if (rxResult.trace().outcome() == StepOutcome.FAILED || rxResult.trace().outcome() == StepOutcome.TIMEOUT) {
            warnings.add("relix_step_" + rxResult.trace().outcome().name().toLowerCase());
        }

        if (sqResult.trace().outcome() != StepOutcome.OK && rxResult.trace().outcome() != StepOutcome.OK) {
            long totalMs = System.currentTimeMillis() - globalStart;
            traces.add(skipFusion(totalMs));
            return new ExecutionResult(List.of(), null, traces, warnings, totalMs, true);
        }

        // Fusion step
        long fuseStart = System.currentTimeMillis();
        List<Hit> hits = sqResult.hits();
        GraphResult graph = rxResult.graph();

        List<Hit> fused;
        if (!hits.isEmpty() && graph != null) {
            fused = fusionEngine.fuse(hits, graph, topK, props.fusion().graphPromotionBonus());
        } else {
            fused = hits.stream().limit(topK).toList();
        }
        long fuseDuration = System.currentTimeMillis() - fuseStart;
        long totalMs = System.currentTimeMillis() - globalStart;

        traces.add(new StepTrace("step-fusion", "gateway", fuseStart - globalStart, fuseDuration, StepOutcome.OK, null));
        return new ExecutionResult(fused, graph, traces, warnings, totalMs, false);
    }

    private ExecutionResult executeGraphOnly(
            String tenant, String query, Map<String, Object> slots, int topK, long globalStart
    ) {
        List<StepTrace> traces = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Future<RelixResult> relixFuture = executor.submit(
                graphCallable(tenant, query, slots, props.stepTimeoutMs(), globalStart)
        );
        RelixResult rxResult = resolve(relixFuture, props.stepTimeoutMs());
        traces.add(rxResult.trace());

        if (rxResult.trace().outcome() != StepOutcome.OK) {
            warnings.add("relix_step_" + rxResult.trace().outcome().name().toLowerCase());
        }
        long totalMs = System.currentTimeMillis() - globalStart;
        return new ExecutionResult(List.of(), rxResult.graph(), traces, warnings, totalMs, false);
    }

    private ExecutionResult executeSearchOnly(
            String tenant, String query, Map<String, Object> slots, int topK, long globalStart
    ) {
        List<StepTrace> traces = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Future<SynquestResult> synquestFuture = executor.submit(
                searchCallable(tenant, query, topK, props.stepTimeoutMs(), globalStart)
        );
        SynquestResult sqResult = resolve(synquestFuture, props.stepTimeoutMs());
        traces.add(sqResult.trace());

        if (sqResult.trace().outcome() != StepOutcome.OK) {
            warnings.add("synquest_step_" + sqResult.trace().outcome().name().toLowerCase());
        }
        List<Hit> hits = sqResult.hits().stream().limit(topK).toList();
        long totalMs = System.currentTimeMillis() - globalStart;
        return new ExecutionResult(hits, null, traces, warnings, totalMs, false);
    }

    private Callable<SynquestResult> searchCallable(
            String tenant, String query, int topK, long timeoutMs, long globalStart
    ) {
        return () -> {
            long start = System.currentTimeMillis();
            try {
                SynquestClient.SynquestResponse resp = synquestClient.search(tenant, query, topK);
                long duration = System.currentTimeMillis() - start;
                StepTrace trace = new StepTrace("step-search", "synquest", start - globalStart, duration, StepOutcome.OK, null);
                return new SynquestResult(resp.hits() != null ? resp.hits() : List.of(), trace);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                boolean timedOut = e.getMessage() != null && e.getMessage().contains("timeout");
                StepOutcome outcome = timedOut ? StepOutcome.TIMEOUT : StepOutcome.FAILED;
                StepTrace trace = new StepTrace("step-search", "synquest", start - globalStart, duration, outcome, e.getMessage());
                return new SynquestResult(List.of(), trace);
            }
        };
    }

    private Callable<RelixResult> graphCallable(
            String tenant, String query, Map<String, Object> slots, long timeoutMs, long globalStart
    ) {
        return () -> {
            long start = System.currentTimeMillis();
            try {
                RelixClient.RelixResponse resp = relixClient.graphQuery(tenant, query, slots);
                long duration = System.currentTimeMillis() - start;
                GraphResult graph = new GraphResult(resp.entities(), resp.edges(), resp.paths());
                StepTrace trace = new StepTrace("step-graph", "relix", start - globalStart, duration, StepOutcome.OK, null);
                return new RelixResult(graph, trace);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                boolean timedOut = e.getMessage() != null && e.getMessage().contains("timeout");
                StepOutcome outcome = timedOut ? StepOutcome.TIMEOUT : StepOutcome.FAILED;
                StepTrace trace = new StepTrace("step-graph", "relix", start - globalStart, duration, outcome, e.getMessage());
                return new RelixResult(null, trace);
            }
        };
    }

    private <T> T resolve(Future<T> future, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("step timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    private StepTrace skipFusion(long totalMs) {
        return new StepTrace("step-fusion", "gateway", totalMs, 0, StepOutcome.SKIPPED, "all_steps_failed");
    }

    record SynquestResult(List<Hit> hits, StepTrace trace) {}
    record RelixResult(GraphResult graph, StepTrace trace) {}

    public record ExecutionResult(
            List<Hit> hits,
            GraphResult graphResult,
            List<StepTrace> traces,
            List<String> warnings,
            long totalMs,
            boolean allFailed
    ) {}
}
