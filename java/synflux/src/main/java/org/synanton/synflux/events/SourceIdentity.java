package org.synanton.synflux.events;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Canonical source identity carried by ingestion events (EWP-2 Migration 3). */
public record SourceIdentity(
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("connector_id") String connectorId,
        @JsonProperty("source_id") String sourceId,
        @JsonProperty("resource_id") String resourceId) {}
