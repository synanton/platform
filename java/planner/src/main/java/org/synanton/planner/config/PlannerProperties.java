package org.synanton.planner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "planner")
public record PlannerProperties(
        Relix relix,
        Labels labels,
        Llm llm
) {
    public record Relix(String baseUrl) {}

    public record Labels(int maxSize, int refreshIntervalSeconds) {}

    public record Llm(
            boolean enabled,
            List<String> enabledTenants,
            String baseUrl,
            String model,
            long timeoutMs,
            double temperature,
            int maxTokens,
            int seed,
            int maxRetries
    ) {}

    public PlannerProperties {
        if (relix == null) relix = new Relix("http://relix:8084");
        if (labels == null) labels = new Labels(50_000, 300);
        if (llm == null) llm = new Llm(
                false, List.of(),
                "http://vllm-llm:8000/v1",
                "llama-3.1-8b-instruct",
                2000L, 0.0, 150, 42, 1);
    }
}
