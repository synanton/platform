package org.synanton.eventing.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantScopeTest {

    @Test
    void acceptsMatchingTenantAndReturnsTheMessage() {
        Event<String> event = event("tenant-a");

        assertThat(TenantScope.requireMatches("tenant-a", event)).isSameAs(event);
    }

    @Test
    void rejectsMissingOrMismatchedExpectedTenant() {
        Event<String> event = event("tenant-a");

        assertThatThrownBy(() -> TenantScope.requireMatches(" ", event))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantScope.requireMatches("tenant-b", event))
                .isInstanceOf(TenantScopeMismatchException.class);
    }

    @Test
    void checksCommandsAtTheSameContractBoundary() {
        Command<String> command = new Command<>(
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "GenerateEmbedding",
                1,
                Instant.parse("2026-10-23T12:00:00Z"),
                "tenant-a",
                new SecurityMetadata(
                        ActorKind.SERVICE, "content-cache", "UNCLASSIFIED", 1, List.of()),
                new CommandTarget("document", "document-42"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                Optional.empty(),
                Optional.empty(),
                "payload");

        assertThatThrownBy(() -> TenantScope.requireMatches("tenant-b", command))
                .isInstanceOf(TenantScopeMismatchException.class);
    }

    private static Event<String> event(String tenantId) {
        return new Event<>(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "ContentPublished",
                1,
                Instant.parse("2026-10-23T12:00:00Z"),
                new Producer("content-cache", "1.26"),
                tenantId,
                Optional.empty(),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                Optional.empty(),
                ReplayClass.REPLAY_SAFE,
                Optional.empty(),
                new SecurityMetadata(
                        ActorKind.SERVICE, "content-cache", "UNCLASSIFIED", 1, List.of()),
                "payload");
    }
}
