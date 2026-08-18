package org.synanton.planner.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.synanton.planner.config.PlannerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Component
public class ClassifierRouter {

    private static final Logger log = LoggerFactory.getLogger(ClassifierRouter.class);

    public record Decision(
            String templateId,
            String intent,
            double confidence,
            String classifiedBy,
            String llmModel,
            Double llmConfidence,
            Long llmLatencyMs,
            String fallbackReason,
            List<String> signals
    ) {}

    private final LlmIntentClassifier llmClassifier;
    private final IntentClassifier heuristicClassifier;
    private final PlannerProperties props;
    private final MeterRegistry meterRegistry;

    public ClassifierRouter(LlmIntentClassifier llmClassifier,
                            IntentClassifier heuristicClassifier,
                            PlannerProperties props,
                            MeterRegistry meterRegistry) {
        this.llmClassifier = llmClassifier;
        this.heuristicClassifier = heuristicClassifier;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    public Decision classify(String query, String tenant, Set<String> entityTypes,
                             List<String> relationVerbs) {
        if (isLlmEnabled(tenant)) {
            return classifyWithLlm(query, tenant, entityTypes, relationVerbs);
        }
        return classifyHeuristic(query, "flag_disabled");
    }

    private boolean isLlmEnabled(String tenant) {
        if (!props.llm().enabled()) return false;
        List<String> allowed = props.llm().enabledTenants();
        if (allowed == null || allowed.isEmpty()) return true;
        return allowed.contains(tenant);
    }

    private Decision classifyWithLlm(String query, String tenant,
                                      Set<String> entityTypes, List<String> relationVerbs) {
        try {
            long t0 = System.currentTimeMillis();
            IntentClassificationResult result = llmClassifier.classify(query, entityTypes, relationVerbs);
            long latencyMs = System.currentTimeMillis() - t0;

            meterRegistry.counter("planner_llm_classify_total",
                    "intent", result.intent(), "tenant", tenant).increment();
            meterRegistry.timer("planner_llm_latency_ms").record(
                    java.time.Duration.ofMillis(latencyMs));

            String templateId = intentToTemplateId(result.intent());
            List<String> signals = buildLlmSignals(result);
            return new Decision(templateId, result.intent(), result.confidence(),
                    "llm", result.llmModel(), result.confidence(), result.llmLatencyMs(),
                    null, signals);

        } catch (TimeoutException e) {
            log.warn("LLM classifier timed out for query '{}', falling back to heuristic", query);
            meterRegistry.counter("planner_llm_fallback_total",
                    "reason", "llm_timeout", "tenant", tenant).increment();
            return classifyHeuristic(query, "llm_timeout");

        } catch (LlmIntentClassifier.LlmClassificationException e) {
            log.warn("LLM classifier error for query '{}': {}, falling back to heuristic",
                    query, e.getMessage());
            meterRegistry.counter("planner_llm_fallback_total",
                    "reason", "llm_error", "tenant", tenant).increment();
            return classifyHeuristic(query, "llm_error");
        }
    }

    private Decision classifyHeuristic(String query, String fallbackReason) {
        String templateId = heuristicClassifier.classify(query);
        double confidence = heuristicClassifier.confidence(query, templateId);
        String intent = templateIdToIntent(templateId);
        List<String> signals = List.of("heuristic_template:" + templateId);
        return new Decision(templateId, intent, confidence,
                "heuristic", null, null, null,
                fallbackReason, signals);
    }

    /** Map LLM intent → execution template ID */
    static String intentToTemplateId(String intent) {
        return switch (intent) {
            case "GRAPH_ONLY" -> "T2";
            case "HYBRID" -> "T1";
            default -> "T3"; // RETRIEVAL_ONLY
        };
    }

    /** Map heuristic template ID → intent string */
    static String templateIdToIntent(String templateId) {
        return switch (templateId) {
            case "T1" -> "HYBRID";
            case "T2" -> "GRAPH_ONLY";
            case "T4" -> "HYBRID";
            default -> "RETRIEVAL_ONLY"; // T3
        };
    }

    private List<String> buildLlmSignals(IntentClassificationResult r) {
        var signals = new java.util.ArrayList<String>();
        r.entityHints().forEach(h -> signals.add("entity_hint:" + h));
        r.relationHints().forEach(h -> signals.add("relation_hint:" + h));
        return signals;
    }
}
