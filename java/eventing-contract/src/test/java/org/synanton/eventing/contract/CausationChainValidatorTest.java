package org.synanton.eventing.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CausationChainValidatorTest {

    private static final UUID CORRELATION = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID ROOT_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID CHILD_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @Test
    void validatesRootAndChildAgainstOneCorrelationAndTenant() {
        Event<String> root = event(ROOT_ID, Optional.empty(), "tenant-a");
        Event<String> child = event(CHILD_ID, Optional.of(ROOT_ID), "tenant-a");

        assertThatCode(() -> CausationChainValidator.validateCompleteChain(List.of(root, child)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingParentDuplicateIdsCrossTenantLinksAndDisconnectedCycles() {
        Event<String> root = event(ROOT_ID, Optional.empty(), "tenant-a");
        Event<String> child = event(CHILD_ID, Optional.of(ROOT_ID), "tenant-a");
        Event<String> orphan = event(CHILD_ID, Optional.of(UUID.randomUUID()), "tenant-a");
        Event<String> crossTenant = event(CHILD_ID, Optional.of(ROOT_ID), "tenant-b");

        assertThatThrownBy(() -> CausationChainValidator.validateCompleteChain(List.of(root, orphan)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CausationChainValidator.validateCompleteChain(List.of(root, child, child)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CausationChainValidator.validateCompleteChain(List.of(root, crossTenant)))
                .isInstanceOf(IllegalArgumentException.class);

        Event<String> cycleA = event(ROOT_ID, Optional.of(CHILD_ID), "tenant-a");
        Event<String> cycleB = event(CHILD_ID, Optional.of(ROOT_ID), "tenant-a");
        assertThatThrownBy(() -> CausationChainValidator.validateCompleteChain(List.of(cycleA, cycleB)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Event<String> event(UUID id, Optional<UUID> cause, String tenantId) {
        return new Event<>(
                id,
                "ContentPublished",
                1,
                Instant.parse("2026-10-23T12:00:00Z"),
                new Producer("content-cache", "1.26"),
                tenantId,
                Optional.empty(),
                CORRELATION,
                cause,
                ReplayClass.REPLAY_SAFE,
                Optional.empty(),
                new SecurityMetadata(
                        ActorKind.SERVICE, "content-cache", "UNCLASSIFIED", 1, List.of()),
                "payload");
    }
}
