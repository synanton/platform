package org.synanton.eventing.contract;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** An immutable, versioned fact. Its identity remains stable across delivery retries. */
@JsonPropertyOrder({
    "event_id",
    "event_type",
    "event_version",
    "occurred_at",
    "producer",
    "tenant",
    "tenant_id",
    "subject_id",
    "correlation_id",
    "causation_id",
    "replay_class",
    "replay_condition",
    "security",
    "payload"
})
public record Event<T>(
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("event_version") int eventVersion,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("producer") Producer producer,
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("tenant") TenantReference tenant,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @JsonProperty("subject_id") Optional<String> subjectId,
        @JsonProperty("correlation_id") UUID correlationId,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @JsonProperty("causation_id") Optional<UUID> causationId,
        @JsonProperty("replay_class") ReplayClass replayClass,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @JsonProperty("replay_condition") Optional<String> replayCondition,
        @JsonProperty("security") SecurityMetadata security,
        @JsonProperty("payload") T payload) implements TenantScopedMessage {

    /** Convenience constructor creates both frozen tenant representations from one tenant ID. */
    public Event(
            UUID eventId,
            String eventType,
            int eventVersion,
            Instant occurredAt,
            Producer producer,
            String tenantId,
            Optional<String> subjectId,
            UUID correlationId,
            Optional<UUID> causationId,
            ReplayClass replayClass,
            Optional<String> replayCondition,
            SecurityMetadata security,
            T payload) {
        this(
                eventId,
                eventType,
                eventVersion,
                occurredAt,
                producer,
                tenantId,
                new TenantReference(tenantId),
                subjectId,
                correlationId,
                causationId,
                replayClass,
                replayCondition,
                security,
                payload);
    }

    public Event {
        Objects.requireNonNull(eventId, "eventId");
        ContractValues.requireText(eventType, "eventType");
        ContractValues.requirePositive(eventVersion, "eventVersion");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(producer, "producer");
        ContractValues.requireTenantId(tenantId);
        Objects.requireNonNull(tenant, "tenant");
        if (!tenantId.equals(tenant.id())) {
            throw new IllegalArgumentException("tenant.id must match tenant_id");
        }
        Objects.requireNonNull(subjectId, "subjectId");
        subjectId.ifPresent(value -> ContractValues.requireText(value, "subjectId"));
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(causationId, "causationId");
        Objects.requireNonNull(replayClass, "replayClass");
        Objects.requireNonNull(replayCondition, "replayCondition");
        replayCondition.ifPresent(value -> ContractValues.requireText(value, "replayCondition"));
        if (replayClass == ReplayClass.REPLAY_WITH_POLICY && replayCondition.isEmpty()) {
            throw new IllegalArgumentException("REPLAY_WITH_POLICY requires a replayCondition");
        }
        if (replayClass != ReplayClass.REPLAY_WITH_POLICY && replayCondition.isPresent()) {
            throw new IllegalArgumentException("replayCondition is only valid for REPLAY_WITH_POLICY");
        }
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(payload, "payload");
    }

    @Override
    public UUID messageId() {
        return eventId;
    }
}
