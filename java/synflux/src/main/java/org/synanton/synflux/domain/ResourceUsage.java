package org.synanton.synflux.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceUsage(
    long wallMs,
    long cpuNs,
    long inputBytes,
    long outputChars,
    long modelInputChars,
    long modelOutputChars,
    int modelInputTokens,
    int modelOutputTokens,
    String modelId,
    List<StageUsage> stages
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ResourceUsage empty() {
        return new ResourceUsage(0, 0, 0, 0, 0, 0, 0, 0, null, List.of());
    }

    public static ResourceUsage fromStages(List<StageUsage> stages) {
        if (stages == null || stages.isEmpty()) {
            return empty();
        }
        long wallMs = stages.stream().mapToLong(StageUsage::wallMs).sum();
        long cpuNs = stages.stream().mapToLong(StageUsage::cpuNs).sum();
        long inputBytes = stages.stream().mapToLong(StageUsage::inputBytes).sum();
        long outputChars = stages.stream().mapToLong(StageUsage::outputChars).sum();
        long modelInputChars = stages.stream().mapToLong(StageUsage::inputChars).sum();
        long modelOutputChars = stages.stream().mapToLong(StageUsage::outputChars).sum();
        int modelInputTokens = stages.stream().mapToInt(StageUsage::modelInputTokens).sum();
        int modelOutputTokens = stages.stream().mapToInt(StageUsage::modelOutputTokens).sum();
        String modelId = stages.stream()
            .map(StageUsage::modelId)
            .filter(id -> id != null && !id.isBlank())
            .reduce((first, second) -> second)
            .orElse(null);
        return new ResourceUsage(
            wallMs, cpuNs, inputBytes, outputChars,
            modelInputChars, modelOutputChars,
            modelInputTokens, modelOutputTokens,
            modelId, List.copyOf(stages));
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ResourceUsage", e);
        }
    }

    public static ResourceUsage fromJson(String json) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        try {
            return MAPPER.readValue(json, ResourceUsage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize ResourceUsage", e);
        }
    }
}
