package org.synanton.topology.domain;

import org.junit.jupiter.api.Test;
import org.synanton.common.grpc.validation.PgvRuleCatalogue;
import org.synanton.eventing.outbox.BusinessWork;
import org.synanton.topology.domain.event.GrantCreated;
import org.synanton.topology.domain.model.OrganizationPolicy;
import org.synanton.topology.domain.model.PropagationId;
import org.synanton.topology.infra.events.TopologyEventPublisher;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrantMutationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T07:00:00Z");
    private static final UUID OUTBOX_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @Test
    void shouldGrantAndWriteAuditHashes() {
        AtomicReference<String> before = new AtomicReference<>("");
        AtomicReference<String> after = new AtomicReference<>();
        AtomicReference<UUID> grantId = new AtomicReference<>();
        GrantMutationService service = service(before, after, grantId);

        PropagationId result = service.grant(command());

        assertThat(result.state()).isEqualTo(PropagationId.PENDING);
        assertThat(result.outboxId()).isEqualTo(OUTBOX_ID);
        assertThat(grantId.get()).isNotNull();
        assertThat(after.get()).isNotBlank();
        assertThat(after.get()).isNotEqualTo(StateHasher.sha256(""));
    }

    @Test
    void shouldRejectInvalidResourceType() {
        GrantMutationService service = service(
                new AtomicReference<>(""), new AtomicReference<>(), new AtomicReference<>());
        GrantMutationService.GrantCommand invalid = new GrantMutationService.GrantCommand(
                "demo", "user:alice", "USER",
                "11111111-1111-1111-1111-111111111111", "WIDGET", "READ", "k1",
                "admin", "USER_SUBJECT", "user");
        assertThatThrownBy(() -> service.grant(invalid))
                .isInstanceOf(GrantMutationService.InvalidGrantException.class);
    }

    private static GrantMutationService.GrantCommand command() {
        return new GrantMutationService.GrantCommand(
                "demo", "user:alice", "USER",
                "11111111-1111-1111-1111-111111111111", "DOCUMENT", "READ", "k1",
                "admin", "USER_SUBJECT", "user");
    }

    private static GrantMutationService service(
            AtomicReference<String> beforeHash,
            AtomicReference<String> afterHash,
            AtomicReference<UUID> grantId
    ) {
        TopologyEventPublisher events = mock(TopologyEventPublisher.class);
        when(events.publishGrantCreated(anyString(), any(GrantCreated.class), any(BusinessWork.class)))
                .thenAnswer(invocation -> {
                    invocation.<BusinessWork>getArgument(2).execute(null);
                    return OUTBOX_ID;
                });
        return new GrantMutationService(
                new PgvRuleCatalogue(),
                new GrantMutationService.GrantStore() {
                    @Override
                    public UUID insertPending(Connection c, GrantMutationService.GrantCommand command, Instant now) {
                        UUID id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
                        grantId.set(id);
                        return id;
                    }

                    @Override
                    public void markPropagated(UUID id, Instant at) {}

                    @Override
                    public void markStuck(UUID id) {}

                    @Override
                    public String currentStateJson(String tenantId, String subjectId, String resourceId) {
                        return "";
                    }
                },
                (c, actor, type, role, tenant, action, resource, before, after, payload) -> {
                    beforeHash.set(before);
                    afterHash.set(after);
                },
                tenantId -> new OrganizationPolicy(
                        "demo", "HIGH_SECURITY", List.of("us-east-1"), "ALWAYS", "bge-reranker-large",
                        100, 20, 1000, 100, 8, 32000, Map.of()),
                Clock.fixed(NOW, ZoneOffset.UTC),
                events
        );
    }
}
