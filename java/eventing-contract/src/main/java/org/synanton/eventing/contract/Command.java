package org.synanton.eventing.contract;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A versioned request for work. Redelivery reuses {@code commandId}; it does not make a new command. */
@JsonPropertyOrder({
    "command_id",
    "command_type",
    "command_version",
    "issued_at",
    "tenant_id",
    "security",
    "target",
    "correlation_id",
    "causation_id",
    "expected_version",
    "payload"
})
public record Command<T>(
        @JsonProperty("command_id") UUID commandId,
        @JsonProperty("command_type") String commandType,
        @JsonProperty("command_version") int commandVersion,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonProperty("issued_at") Instant issuedAt,
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("security") SecurityMetadata security,
        @JsonProperty("target") CommandTarget target,
        @JsonProperty("correlation_id") UUID correlationId,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @JsonProperty("causation_id") Optional<UUID> causationId,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @JsonProperty("expected_version") Optional<Long> expectedVersion,
        @JsonProperty("payload") T payload) implements TenantScopedMessage {

    public Command {
        Objects.requireNonNull(commandId, "commandId");
        ContractValues.requireText(commandType, "commandType");
        ContractValues.requirePositive(commandVersion, "commandVersion");
        Objects.requireNonNull(issuedAt, "issuedAt");
        ContractValues.requireTenantId(tenantId);
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(causationId, "causationId");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        Objects.requireNonNull(payload, "payload");
        expectedVersion.ifPresent(version -> {
            if (version < 0) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
        });
    }

    @Override
    public UUID messageId() {
        return commandId;
    }
}
