package org.synanton.mcp.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synanton-mcp")
public record McpProperties(
    String securityUrl,
    String synaptUrl,
    String relixUrl,
    String syntologyUrl,
    long toolTimeoutMs,
    AuthCache authCache
) {
    public record AuthCache(long ttlSeconds, long maxSize) {}

    public String securityUrl() { return securityUrl != null ? securityUrl : "http://localhost:8081"; }
    public String synaptUrl() { return synaptUrl != null ? synaptUrl : "http://localhost:8085"; }
    public String relixUrl() { return relixUrl != null ? relixUrl : "http://localhost:8084"; }
    public String syntologyUrl() { return syntologyUrl != null ? syntologyUrl : "http://localhost:8083"; }
    public long toolTimeoutMs() { return toolTimeoutMs > 0 ? toolTimeoutMs : 5000; }
}
