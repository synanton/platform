package org.synanton.topology.domain.model;

import java.math.BigDecimal;

public record TenantPolicy(String tenantId, int qpsLimit, BigDecimal monthlyUsdLimit, int maxLatencyMs) {}
