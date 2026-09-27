package org.synanton.synvault.cassandra;

import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.storage.testkit.SynvaultStoreContract;
import org.synanton.synvault.api.SynvaultStore;

/**
 * {@link SynvaultStoreContract} against a real Cassandra (testcontainers, image cached
 * locally). Container is shared per JVM via {@link CassandraTestBase}; each test gets
 * an isolated store via a unique namespace — no schema churn.
 */
class CassandraSynvaultStoreTest extends SynvaultStoreContract {

    @BeforeAll
    static void startCassandra() {
        CassandraTestBase.ensureStarted();
    }

    @Override
    protected SynvaultStore newStore() {
        return new CassandraSynvaultStore(CassandraTestBase.client, "test-" + UUID.randomUUID());
    }

    @Test
    void operationsAreRecordedByDefault() {
        var store = new CassandraSynvaultStore(CassandraTestBase.client, "test-" + UUID.randomUUID());
        var ctx =
                new org.synanton.storage.contract.SecurityContext(
                        org.synanton.storage.contract.TenantScope.of("tenant_a"),
                        java.util.List.of(org.synanton.storage.contract.PrincipalRef.user("u-1")),
                        org.synanton.storage.contract.PolicyContext.of("p", "r1"),
                        true,
                        false);
        store.getDocument(ctx, org.synanton.storage.contract.DocumentId.of("missing"))
                .toCompletableFuture()
                .join();
        org.assertj.core.api.Assertions.assertThat(
                        store.metricsForTesting().snapshot().operations()
                                .get(org.synanton.storage.contract.AdapterMetrics.SYNVAULT_GET)
                                .count())
                .isEqualTo(1);
    }

    @Test
    void startupValidationRejectsCassandraWhereRevisionRequired() {
        // 008 decision, enforced through 039: the REAL matrix (not a stub) must fail
        // fast when the deployment requires revision semantics.
        var registry = new org.synanton.storage.provider.ProviderRegistry();
        var adapter = new CassandraSynvaultStore(CassandraTestBase.client, "test-" + UUID.randomUUID());
        registry.register(org.synanton.storage.provider.ProviderRegistry.PORT_SYNVAULT, "cassandra", adapter);
        var quest = new org.synanton.synquest.inmemory.InMemorySynquestEngine();
        registry.register(org.synanton.storage.provider.ProviderRegistry.PORT_SYNQUEST, "inmemory", quest);
        registry.register(org.synanton.storage.provider.ProviderRegistry.PORT_WRITER, "inmemory", quest);
        registry.register(org.synanton.storage.provider.ProviderRegistry.PORT_ADMIN, "inmemory", quest);
        org.synanton.storage.provider.ProviderSelection selection =
                new org.synanton.storage.provider.ProviderSelection("cassandra", "inmemory", "inmemory", "inmemory");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                registry.validate(
                                        selection,
                                        org.synanton.storage.provider.DeploymentRequirements.fullRevision(false)))
                .isInstanceOf(org.synanton.storage.contract.ProviderIncompatibleException.class)
                .hasMessageContaining("cassandra")
                .hasMessageContaining(org.synanton.storage.contract.Capabilities.SYNVAULT_REVISION);
    }
}
