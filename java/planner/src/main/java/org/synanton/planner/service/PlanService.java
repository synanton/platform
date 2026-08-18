package org.synanton.planner.service;

import org.synanton.planner.config.PlannerProperties;
import org.synanton.planner.domain.PlanRequest;
import org.synanton.planner.domain.PlanResult;
import org.synanton.planner.domain.PlanTrace;
import org.synanton.planner.domain.PlannerStats;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PlanService {

    private final ClassifierRouter classifierRouter;
    private final SlotExtractor slotExtractor;
    private final EntityLabelIndex labelIndex;
    private final PlannerProperties props;
    private final AtomicLong totalPlans = new AtomicLong(0);

    public PlanService(ClassifierRouter classifierRouter,
                       SlotExtractor slotExtractor,
                       EntityLabelIndex labelIndex,
                       PlannerProperties props) {
        this.classifierRouter = classifierRouter;
        this.slotExtractor = slotExtractor;
        this.labelIndex = labelIndex;
        this.props = props;
    }

    public PlanResult plan(PlanRequest req) {
        long t0 = System.currentTimeMillis();

        List<String> relationVerbs = props.llm() != null
                ? List.of() // relation verbs come from intent config in future phases
                : List.of();

        ClassifierRouter.Decision decision = classifierRouter.classify(
                req.query(),
                req.tenant() != null ? req.tenant() : "demo",
                labelIndex.getLabels(),
                relationVerbs);

        Map<String, Object> slots = slotExtractor.extract(req.query(), labelIndex.getLabels());
        totalPlans.incrementAndGet();

        long plannerMs = System.currentTimeMillis() - t0;

        PlanTrace trace = buildTrace(decision, plannerMs);
        return new PlanResult(decision.templateId(), decision.intent(), req.query(),
                req.tenant(), slots, decision.confidence(), trace);
    }

    public PlannerStats stats() {
        return new PlannerStats(labelIndex.getLabels().size(), labelIndex.getLastRefresh(), totalPlans.get());
    }

    private PlanTrace buildTrace(ClassifierRouter.Decision d, long plannerMs) {
        if ("llm".equals(d.classifiedBy())) {
            return PlanTrace.llm(d.llmModel(), d.llmConfidence(), d.llmLatencyMs(),
                    d.signals(), plannerMs);
        }
        if (d.fallbackReason() != null) {
            return PlanTrace.heuristicFallback(d.signals(), d.fallbackReason(), plannerMs);
        }
        return PlanTrace.heuristic(d.signals(), plannerMs);
    }
}
