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

class GrantMutationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T07:00:00Z");

    @Test
    void shouldGrantAndWriteAuditHashes() {
        AtomicReference<String> before = new AtomicReference<>("");
        AtomicReference<String> after = new AtomicReference<>();
        AtomicReference<UUID> grantId = new AtomicReference<>();
        GrantMutationService service = service(before, after, grantId);

        PropagationId result = service.grant(command());

        assertThat(result.state()).isEqualTo(PropagationId.PENDING);
        assertThat(grantId.get()).isNotNull();
        assertThat(after.get()).isNotBlank();
        assertThat(after.get()).isNotEqualTo(StateHasher.sha256(""));
    }

    @Test
    void shouldRejectInvalidResourceType() {
        GrantMutationService service = service(new AtomicReference<>(""), new AtomicReference<>(), new AtomicReference<>());
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
        OrganizationPolicy policy = new OrganizationPolicy(
                "demo", "HIGH_SECURITY", List.of("us-east-1"), "ALWAYS", "bge-reranker-large",
                100, 20, 1000, 100, 8, 32000, Map.of());
        return new GrantMutationService(
                new PgvRuleCatalogue(),
                new GrantMutationService.GrantStore() {
                    @Override
                    public UUID insertPending(GrantMutationService.GrantCommand command, Instant now) {
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
                (eventType, payload, now) -> UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                (actor, type, role, tenant, action, resource, before, after, payload) -> {
                    beforeHash.set(before);
                    afterHash.set(after);
                },
                tenantId -> policy,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
