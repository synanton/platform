package org.synanton.planner.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanTrace(
        @JsonProperty("classified_by") String classifiedBy,
        List<String> signals,
        @JsonProperty("llm_model") String llmModel,
        @JsonProperty("llm_confidence") Double llmConfidence,
        @JsonProperty("llm_latency_ms") Long llmLatencyMs,
        @JsonProperty("fallback_reason") String fallbackReason,
        @JsonProperty("planner_ms") long plannerMs
) {
    public static PlanTrace heuristic(List<String> signals, long plannerMs) {
        return new PlanTrace("heuristic", signals, null, null, null, null, plannerMs);
    }

    public static PlanTrace heuristicFallback(List<String> signals, String fallbackReason, long plannerMs) {
        return new PlanTrace("heuristic", signals, null, null, null, fallbackReason, plannerMs);
    }

    public static PlanTrace llm(String model, double confidence, long llmLatencyMs,
                                 List<String> signals, long plannerMs) {
        return new PlanTrace("llm", signals, model, confidence, llmLatencyMs, null, plannerMs);
    }
}
