package org.synanton.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.synanton.gateway.client.PlannerClient;
import org.synanton.gateway.config.GatewayProperties;
import org.synanton.gateway.domain.ExecutionTrace;
import org.synanton.gateway.domain.GatewayStats;
import org.synanton.gateway.domain.GraphResult;
import org.synanton.gateway.domain.Hit;
import org.synanton.gateway.domain.QueryRequest;
import org.synanton.gateway.domain.QueryResponse;
import org.synanton.gateway.domain.StepTrace;
import org.synanton.gateway.domain.SynthesisTrace;
import org.synanton.gateway.plan.PlanExecutor;
import org.synanton.gateway.synthesis.SynthesisResult;
import org.synanton.gateway.synthesis.SynthesisService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final PlannerClient plannerClient;
    private final PlanExecutor planExecutor;
    private final SynthesisService synthesisService;
    private final GatewayProperties props;

    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong successfulQueries = new AtomicLong(0);
    private final AtomicLong failedQueries = new AtomicLong(0);
    private final AtomicLong timeoutQueries = new AtomicLong(0);
    private final AtomicLong synthesisEnabled = new AtomicLong(0);
    private final AtomicLong totalMs = new AtomicLong(0);

    public QueryService(
            PlannerClient plannerClient,
            PlanExecutor planExecutor,
            SynthesisService synthesisService,
            GatewayProperties props
    ) {
        this.plannerClient = plannerClient;
        this.planExecutor = planExecutor;
        this.synthesisService = synthesisService;
        this.props = props;
    }

    public QueryResponse query(QueryRequest request) {
        totalQueries.incrementAndGet();
        long start = System.currentTimeMillis();

        List<String> warnings = new ArrayList<>();

        // Step 1: Get plan from planner
        PlannerClient.PlannerResponse plan;
        try {
            plan = plannerClient.plan(request.query(), request.tenant());
        } catch (PlannerClient.PlannerUnavailableException e) {
            log.warn("Planner unavailable: {}", e.getMessage());
            warnings.add("planner_unavailable");
            failedQueries.incrementAndGet();
            long elapsed = System.currentTimeMillis() - start;
            ExecutionTrace trace = new ExecutionTrace(null, List.of(), null, elapsed, warnings);
            return new QueryResponse(List.of(), null, null, trace);
        }

        Map<String, Object> slots = plan.slots() != null ? plan.slots() : Map.of();

        // Step 2: Execute plan (parallel steps + fusion)
        PlanExecutor.ExecutionResult execResult = planExecutor.execute(
                plan,
                request.tenant(),
                request.query(),
                request.effectiveTopK(),
                slots
        );

        warnings.addAll(execResult.warnings());

        if (execResult.allFailed()) {
            warnings.add("all_steps_failed");
            failedQueries.incrementAndGet();
        }

        List<Hit> hits = execResult.hits();
        GraphResult graphResult = execResult.graphResult();
        List<StepTrace> stepTraces = new ArrayList<>(execResult.traces());

        // Step 3 (Phase 2): Synthesis
        String answer = null;
        SynthesisTrace synthesisTrace = null;

        SynthesisResult synthResult = synthesisService.synthesise(request.query(), hits, graphResult);

        if (synthResult instanceof SynthesisResult.Ok ok) {
            answer = ok.answer();
            synthesisTrace = new SynthesisTrace(
                    props.synthesis().model(),
                    ok.promptTokens(),
                    ok.completionTokens(),
                    ok.latencyMs(),
                    "OK"
            );
            synthesisEnabled.incrementAndGet();
        } else if (synthResult instanceof SynthesisResult.Timeout t) {
            synthesisTrace = new SynthesisTrace(props.synthesis().model(), 0, 0, t.latencyMs(), "TIMEOUT");
            warnings.add("synthesis_timeout");
        } else if (synthResult instanceof SynthesisResult.Error err) {
            synthesisTrace = new SynthesisTrace(props.synthesis().model(), 0, 0, err.latencyMs(), "ERROR");
            warnings.add("synthesis_error");
        } else if (synthResult instanceof SynthesisResult.SkippedEmpty) {
            synthesisTrace = new SynthesisTrace(null, 0, 0, 0, "SKIPPED_EMPTY");
        } else if (synthResult instanceof SynthesisResult.Disabled) {
            synthesisTrace = new SynthesisTrace(null, 0, 0, 0, "DISABLED");
        }

        long elapsed = System.currentTimeMillis() - start;
        totalMs.addAndGet(elapsed);

        if (!execResult.allFailed()) {
            successfulQueries.incrementAndGet();
        }

        ExecutionTrace executionTrace = new ExecutionTrace(plan, stepTraces, synthesisTrace, elapsed, warnings);
        return new QueryResponse(hits, graphResult, answer, executionTrace);
    }

    public GatewayStats stats() {
        long total = totalQueries.get();
        long avgMs = total > 0 ? totalMs.get() / total : 0;
        return new GatewayStats(
                total,
                successfulQueries.get(),
                failedQueries.get(),
                timeoutQueries.get(),
                avgMs,
                synthesisEnabled.get()
        );
    }
}
