package org.synanton.topology.domain;

import org.junit.jupiter.api.Test;
import org.synanton.common.grpc.validation.PgvRuleCatalogue;
import org.synanton.topology.domain.model.OrganizationPolicy;
import org.synanton.topology.domain.model.PropagationId;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassGrantMutationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T09:00:00Z");

    @Test
    void shouldGrantClassAndWriteAuditHashes() {
        AtomicReference<String> after = new AtomicReference<>();
        AtomicReference<UUID> grantId = new AtomicReference<>();
        ClassGrantMutationService service = service(after, grantId);

        PropagationId result = service.grant(command());

        assertThat(result.state()).isEqualTo(PropagationId.PENDING);
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
        OrganizationPolicy policy = new OrganizationPolicy(
                "demo", "STANDARD", List.of("us-east-1"), "ALWAYS", "bge-reranker-large",
                100, 20, 1000, 100, 8, 32000, Map.of());
        return new ClassGrantMutationService(
                new PgvRuleCatalogue(),
                new ClassGrantMutationService.ClassGrantStore() {
                    @Override
                    public UUID insertPending(ClassGrantMutationService.ClassGrantCommand command, Instant now) {
                        UUID id = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
                        grantId.set(id);
                        return id;
                    }

                    @Override
                    public void markPropagated(UUID id, Instant at) {}

                    @Override
                    public void markStuck(UUID id) {}

                    @Override
                    public void revoke(UUID id, Instant at) {}

                    @Override
                    public String currentStateJson(String tenantId, String subjectId, String sensitivityClass) {
                        return "";
                    }
                },
                (eventType, payload, now) -> UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                (actor, type, role, tenant, action, resource, before, after, payload) -> afterHash.set(after),
                tenantId -> policy,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
