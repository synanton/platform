package org.synanton.controlplane.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "control-plane")
public record ControlPlaneProperties(
        String securityUrl,
        String topologyUrl,
        String adminScope,
        String bootstrapJwt,
        List<ModelEntry> models,
        HttpClientConfig httpClient
) {
    public record ModelEntry(
            String modelId,
            String provider,
            String baseUrl,
            double costPerTokenUsd,
            List<String> capabilities,
            String status,
            String executionPlane  // "gpu" or null/absent for CPU default
    ) {
        public boolean isGpuBacked() {
            return "gpu".equalsIgnoreCase(executionPlane);
        }
    }

    public record HttpClientConfig(int connectTimeoutMs, int readTimeoutMs) {}

    public String securityUrl() {
        return securityUrl != null ? securityUrl : "http://localhost:8081";
    }

    public String topologyUrl() {
        return topologyUrl != null ? topologyUrl : "http://localhost:8082";
    }

    public String adminScope() {
        return adminScope != null ? adminScope : "admin";
    }

    public String bootstrapJwt() {
        return bootstrapJwt != null ? bootstrapJwt : "";
    }

    public List<ModelEntry> models() {
        return models != null ? models : List.of();
    }
}
