package org.synanton.topology.domain;

import org.junit.jupiter.api.Test;
import org.synanton.common.grpc.validation.PgvRuleCatalogue;
import org.synanton.topology.domain.model.OrganizationPolicy;
import org.synanton.topology.domain.model.PropagationId;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrantMutationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T07:00:00Z");

    @Test
    void shouldGrantAndWriteAuditHashes() {
        AtomicReference<String> before = new AtomicReference<>("");
        AtomicReference<String> after = new AtomicReference<>();
        AtomicReference<UUID> grantId = new AtomicReference<>();
        GrantMutationService service = service(before, after, grantId, mockDataSource());

        PropagationId result = service.grant(command());

        assertThat(result.state()).isEqualTo(PropagationId.PENDING);
        assertThat(grantId.get()).isNotNull();
        assertThat(after.get()).isNotBlank();
        assertThat(after.get()).isNotEqualTo(StateHasher.sha256(""));
    }

    @Test
    void shouldRejectInvalidResourceType() {
        GrantMutationService service = service(
                new AtomicReference<>(""), new AtomicReference<>(), new AtomicReference<>(), mockDataSource());
        GrantMutationService.GrantCommand invalid = new GrantMutationService.GrantCommand(
                "demo", "user:alice", "USER",
                "11111111-1111-1111-1111-111111111111", "WIDGET", "READ", "k1",
                "admin", "USER_SUBJECT", "user");
        assertThatThrownBy(() -> service.grant(invalid))
                .isInstanceOf(GrantMutationService.InvalidGrantException.class);
    }

    @Test
    void grant_commitsWhenAllStoresSucceed() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);

        GrantMutationService service = service(
                new AtomicReference<>(""), new AtomicReference<>(), new AtomicReference<>(), dataSource);

        service.grant(command());

        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void grant_rollsBackWhenOutboxInsertFails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);

        OrganizationPolicy policy = policy();
        GrantMutationService service = new GrantMutationService(
                new PgvRuleCatalogue(),
                new GrantMutationService.GrantStore() {
                    @Override
                    public UUID insertPending(Connection c, GrantMutationService.GrantCommand command, Instant now) {
                        return UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
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
                (c, eventType, payload, now) -> {
                    throw new IllegalStateException("outbox unavailable");
                },
                (c, actor, type, role, tenant, action, resource, before, after, payload) -> { },
                tenantId -> policy,
                Clock.fixed(NOW, ZoneOffset.UTC),
                dataSource);

        assertThatThrownBy(() -> service.grant(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");

        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    private static GrantMutationService.GrantCommand command() {
        return new GrantMutationService.GrantCommand(
                "demo", "user:alice", "USER",
                "11111111-1111-1111-1111-111111111111", "DOCUMENT", "READ", "k1",
                "admin", "USER_SUBJECT", "user");
    }

    private static OrganizationPolicy policy() {
        return new OrganizationPolicy(
                "demo", "HIGH_SECURITY", List.of("us-east-1"), "ALWAYS", "bge-reranker-large",
                100, 20, 1000, 100, 8, 32000, Map.of());
    }

    private static GrantMutationService service(
            AtomicReference<String> beforeHash,
            AtomicReference<String> afterHash,
            AtomicReference<UUID> grantId,
            DataSource dataSource
    ) {
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
                (c, eventType, payload, now) -> UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                (c, actor, type, role, tenant, action, resource, before, after, payload) -> {
                    beforeHash.set(before);
                    afterHash.set(after);
                },
                tenantId -> policy(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                dataSource
        );
    }

    private static DataSource mockDataSource() {
        DataSource dataSource = mock(DataSource.class);
        try {
            when(dataSource.getConnection()).thenReturn(mock(Connection.class));
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
        return dataSource;
    }
}
