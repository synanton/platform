package org.synanton.gateway.domain;

public record GatewayStats(
        long totalQueries,
        long successfulQueries,
        long failedQueries,
        long timeoutQueries,
        double avgTotalMs,
        long synthesisEnabledCount
) {}
