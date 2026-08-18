package org.synanton.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        Executor executor,
        long stepTimeoutMs,
        long requestTimeoutMs,
        Fusion fusion,
        Planner planner,
        Synquest synquest,
        Relix relix,
        Synthesis synthesis
) {
    public record Executor(int parallelism) {}
    public record Fusion(String defaultMethod, double graphPromotionBonus) {}
    public record Planner(String baseUrl, long timeoutMs, boolean retryOnce5xx) {}
    public record Synquest(String baseUrl, long timeoutMs) {}
    public record Relix(String baseUrl, long timeoutMs) {}
    public record Synthesis(
            boolean enabled,
            int contextHits,
            int maxContextTokens,
            long timeoutMs,
            double temperature,
            int maxTokens,
            String model,
            String baseUrl
    ) {}

    public GatewayProperties {
        if (executor == null) executor = new Executor(8);
        if (stepTimeoutMs <= 0) stepTimeoutMs = 5000;
        if (requestTimeoutMs <= 0) requestTimeoutMs = 8000;
        if (fusion == null) fusion = new Fusion("content_ref_intersection_first_then_rrf", 0.1);
        if (planner == null) planner = new Planner("http://planner:8085", 3000, true);
        if (synquest == null) synquest = new Synquest("http://synquest:8083", 5000);
        if (relix == null) relix = new Relix("http://relix:8084", 5000);
        if (synthesis == null) synthesis = new Synthesis(
                false, 10, 3000, 8000, 0.3, 150, "llama-3.1-8b-instruct", "http://vllm-llm:8000/v1"
        );
    }
}
