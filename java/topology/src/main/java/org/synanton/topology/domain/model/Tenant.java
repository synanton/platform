package org.synanton.topology.domain.model;

import java.time.Instant;

public record Tenant(String tenantId, String displayName, Instant createdAt) {}
