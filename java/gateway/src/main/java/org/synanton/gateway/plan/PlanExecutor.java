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
import org.synanton.gateway.gpu.GpuRerankAdapter;
import org.synanton.llm.RerankRequest;
import org.synanton.llm.RerankResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    private final SynquestClient synquestClient;
    private final RelixClient relixClient;
    private final FusionEngine fusionEngine;
    private final ExecutorService executor;
    private final GatewayProperties props;
    private final Optional<GpuRerankAdapter> gpuRerankAdapter;

    public PlanExecutor(
            SynquestClient synquestClient,
            RelixClient relixClient,
            FusionEngine fusionEngine,
            ExecutorService executor,
            GatewayProperties props,
            Optional<GpuRerankAdapter> gpuRerankAdapter
    ) {
        this.synquestClient = synquestClient;
        this.relixClient = relixClient;
        this.fusionEngine = fusionEngine;
        this.executor = executor;
        this.props = props;
        this.gpuRerankAdapter = gpuRerankAdapter;
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

        traces.add(new StepTrace("step-fusion", "gateway", fuseStart - globalStart, fuseDuration, StepOutcome.OK, null));

        // Rerank step (Phase 4): use GPU rerank if available
        List<Hit> reranked = rerankIfAvailable(tenant, query, fused, fuseStart, globalStart, traces, warnings);

        long totalMs = System.currentTimeMillis() - globalStart;
        return new ExecutionResult(reranked, graph, traces, warnings, totalMs, false);
    }

    private List<Hit> rerankIfAvailable(
            String tenant,
            String query,
            List<Hit> hits,
            long stepStart,
            long globalStart,
            List<StepTrace> traces,
            List<String> warnings
    ) {
        if (gpuRerankAdapter.isEmpty() || hits.isEmpty()) {
            return hits;
        }

        long rerankStart = System.currentTimeMillis();
        try {
            List<String> passages = hits.stream()
                    .map(h -> h.snippet() != null ? h.snippet() : "")
                    .collect(Collectors.toList());

            // Model will be resolved by adapter based on tenant
            RerankRequest request = new RerankRequest(
                    null, // model - resolved by adapter
                    query,
                    passages,
                    props.rerank().topN()
            );

            RerankResponse response = gpuRerankAdapter.get().rerank(request, tenant);

            if (response.results() != null && !response.results().isEmpty()) {
                // Reorder hits based on rerank scores
                List<Hit> reranked = new ArrayList<>();
                for (RerankResponse.RerankResult result : response.results()) {
                    int idx = result.index();
                    if (idx >= 0 && idx < hits.size()) {
                        Hit original = hits.get(idx);
                        reranked.add(original.withScore(result.score()));
                    }
                }
                // Add any hits that weren't in rerank results (shouldn't happen but safety)
                if (reranked.size() < hits.size()) {
                    for (Hit hit : hits) {
                        boolean found = reranked.stream().anyMatch(h -> h.contentRefId().equals(hit.contentRefId()));
                        if (!found) {
                            reranked.add(hit);
                        }
                    }
                }

                long duration = System.currentTimeMillis() - rerankStart;
                traces.add(new StepTrace("step-rerank", "gpu", stepStart - globalStart, duration, StepOutcome.OK, null));
                log.debug("GPU rerank completed: {} hits reranked in {}ms", reranked.size(), duration);
                return reranked;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - rerankStart;
            log.warn("GPU rerank failed, using original order: {}", e.getMessage());
            traces.add(new StepTrace("step-rerank", "gpu", stepStart - globalStart, duration, StepOutcome.FAILED, e.getMessage()));
            warnings.add("rerank_failed");
        }
        return hits;
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
