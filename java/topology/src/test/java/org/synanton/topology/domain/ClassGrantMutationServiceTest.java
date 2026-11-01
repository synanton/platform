package org.synanton.topology.domain;

import org.junit.jupiter.api.Test;
import org.synanton.common.grpc.validation.PgvRuleCatalogue;
import org.synanton.eventing.outbox.BusinessWork;
import org.synanton.topology.domain.event.ClassGrantUpsert;
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

class ClassGrantMutationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T09:00:00Z");
    private static final UUID OUTBOX_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd");

    @Test
    void shouldGrantClassAndWriteAuditHashes() {
        AtomicReference<String> after = new AtomicReference<>();
        AtomicReference<UUID> grantId = new AtomicReference<>();
        ClassGrantMutationService service = service(after, grantId);

        PropagationId result = service.grant(command());

        assertThat(result.state()).isEqualTo(PropagationId.PENDING);
        assertThat(result.outboxId()).isEqualTo(OUTBOX_ID);
        assertThat(grantId.get()).isNotNull();
        assertThat(after.get()).isNotBlank();
    }

    @Test
    void shouldRejectInvalidSensitivityClass() {
        ClassGrantMutationService service = service(new AtomicReference<>(), new AtomicReference<>());
        ClassGrantMutationService.ClassGrantCommand invalid = new ClassGrantMutationService.ClassGrantCommand(
                "demo", "hr", "GROUP", "SECRET", "SEARCH", "k1",
                "admin", "USER_SUBJECT", "user");
        assertThatThrownBy(() -> service.grant(invalid))
                .isInstanceOf(ClassGrantMutationService.InvalidClassGrantException.class);
    }

    private static ClassGrantMutationService.ClassGrantCommand command() {
        return new ClassGrantMutationService.ClassGrantCommand(
                "demo", "hr", "GROUP", "PERSONAL", "SEARCH", "k1",
                "admin", "USER_SUBJECT", "user");
    }

    private static ClassGrantMutationService service(
            AtomicReference<String> afterHash,
            AtomicReference<UUID> grantId
    ) {
        TopologyEventPublisher events = mock(TopologyEventPublisher.class);
        when(events.publishClassGrantUpsert(anyString(), any(ClassGrantUpsert.class), any(BusinessWork.class)))
                .thenAnswer(invocation -> {
                    invocation.<BusinessWork>getArgument(2).execute(null);
                    return OUTBOX_ID;
                });
        return new ClassGrantMutationService(
                new PgvRuleCatalogue(),
                new ClassGrantMutationService.ClassGrantStore() {
                    @Override
                    public UUID insertPending(Connection c, ClassGrantMutationService.ClassGrantCommand command, Instant now) {
                        UUID id = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
                        grantId.set(id);
                        return id;
                    }

                    @Override
                    public void markPropagated(UUID id, Instant at) {}

                    @Override
                    public void markStuck(UUID id) {}

                    @Override
                    public void revoke(Connection c, UUID id, Instant at) {}

                    @Override
                    public String currentStateJson(String tenantId, String subjectId, String sensitivityClass) {
                        return "";
                    }
                },
                (c, actor, type, role, tenant, action, resource, before, after, payload) -> afterHash.set(after),
                tenantId -> new OrganizationPolicy(
                        "demo", "STANDARD", List.of("us-east-1"), "ALWAYS", "bge-reranker-large",
                        100, 20, 1000, 100, 8, 32000, Map.of()),
                Clock.fixed(NOW, ZoneOffset.UTC),
                events
        );
    }
}
