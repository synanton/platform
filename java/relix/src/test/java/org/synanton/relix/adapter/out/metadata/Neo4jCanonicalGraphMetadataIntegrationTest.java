package org.synanton.relix.adapter.out.metadata;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.relix.adapter.out.graph.neo4j.DriverCypherExecutor;
import org.synanton.relix.identity.CanonicalGraphMetadata;
import org.synanton.relix.identity.CanonicalGraphMetadataBootstrap;
import org.synanton.relix.identity.MissingCanonicalGraphException;
import org.synanton.relix.identity.ProjectionProvisioner;
import org.synanton.relix.identity.ProjectionProvisioningOutcome;
import org.synanton.relix.identity.ProjectionProvisioningResult;
import org.synanton.relix.identity.SourceFenceKey;
import org.synanton.relix.syntology.ActiveOntologyVersion;
import org.synanton.relix.syntology.SyntologyActiveVersionClient;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Neo4jCanonicalGraphMetadataIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    private static Neo4jContainer<?> neo4j;
    private static Driver driver;
    private static DriverCypherExecutor cypher;
    private static Neo4jCanonicalGraphMetadataStore store;

    @BeforeAll
    static void startNeo4j() {
        neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26")).withoutAuthentication();
        neo4j.start();
        driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.none());
        cypher = new DriverCypherExecutor(driver, "neo4j");
        store = new Neo4jCanonicalGraphMetadataStore(cypher);
    }

    @AfterAll
    static void stopNeo4j() {
        if (driver != null) {
            driver.close();
        }
        if (neo4j != null) {
            neo4j.stop();
        }
    }

    @Test
    void concurrentBootstrapConvergesOnOneMetadataVersion() throws Exception {
        String tenant = "tenant-metadata-race";
        seedCanonicalEntity(tenant);
        store.find(tenant);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<GraphIdentity> first = bootstrapTask(start, tenant, "v1.2.0");
            Callable<GraphIdentity> second = bootstrapTask(start, tenant, "v1.3.0");
            Future<GraphIdentity> firstResult = executor.submit(first);
            Future<GraphIdentity> secondResult = executor.submit(second);
            start.countDown();

            GraphIdentity a = firstResult.get(30, TimeUnit.SECONDS);
            GraphIdentity b = secondResult.get(30, TimeUnit.SECONDS);

            assertThat(a).isEqualTo(b);
            OptionalMetadata metadata = readMetadata(tenant);
            assertThat(metadata.count()).isEqualTo(1);
            assertThat(metadata.projectionVersion()).isEqualTo(a.projectionVersion());
            assertThat(a.projectionVersion()).isIn("v1.2.0", "v1.3.0");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentProvisioningCreatesOneMetadataNode() throws Exception {
        String tenant = "tenant-provision-race";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ProjectionProvisioningResult> first = provisionTask(start, tenant, NOW);
            Callable<ProjectionProvisioningResult> second = provisionTask(start, tenant, NOW.plusMillis(1));
            Future<ProjectionProvisioningResult> firstResult = executor.submit(first);
            Future<ProjectionProvisioningResult> secondResult = executor.submit(second);
            start.countDown();

            ProjectionProvisioningResult a = firstResult.get(30, TimeUnit.SECONDS);
            ProjectionProvisioningResult b = secondResult.get(30, TimeUnit.SECONDS);

            assertThat(readMetadata(tenant).count()).isEqualTo(1);
            assertThat(List.of(a.outcome(), b.outcome()))
                    .containsExactlyInAnyOrder(
                            ProjectionProvisioningOutcome.NEWLY_PROVISIONED,
                            ProjectionProvisioningOutcome.ALREADY_INITIALIZED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedProvisioningIsIdempotentAndPreservesCreatedAt() {
        String tenant = "tenant-provision-idempotent";
        ProjectionProvisioningResult first = new ProjectionProvisioner(
                store, new FixedClient(tenant, "v1.2.0"), Clock.fixed(NOW, ZoneOffset.UTC))
                .provision(tenant);
        ProjectionProvisioningResult second = new ProjectionProvisioner(
                store, new FixedClient(tenant, "v1.2.0"),
                Clock.fixed(NOW.plusMillis(1), ZoneOffset.UTC)).provision(tenant);

        assertThat(first.outcome()).isEqualTo(ProjectionProvisioningOutcome.NEWLY_PROVISIONED);
        assertThat(second.outcome()).isEqualTo(ProjectionProvisioningOutcome.ALREADY_INITIALIZED);
        assertThat(second.metadata().createdAt()).isEqualTo(first.metadata().createdAt());
        assertThat(readMetadata(tenant).count()).isEqualTo(1);
    }

    @Test
    void sourceFenceAdvanceIsMonotonicAndIdempotent() {
        SourceFenceKey key = new SourceFenceKey("tenant-fence-store", "source-fence-store");
        Neo4jSourceFenceStore fences = new Neo4jSourceFenceStore(cypher);

        assertThat(fences.read(key)).isEmpty();
        assertThat(fences.advance(key, 3)).isEqualTo(3);
        assertThat(fences.advance(key, 3)).isEqualTo(3);
        assertThat(fences.advance(key, 2)).isEqualTo(3);
        assertThat(fences.advance(key, 5)).isEqualTo(5);
        assertThat(fences.read(key)).contains(5L);
    }

    @Test
    void existingMetadataIsNeverOverwritten() {
        String tenant = "tenant-metadata-existing";
        seedCanonicalEntity(tenant);
        store.bootstrapIfAbsent(new CanonicalGraphMetadata(
                tenant, "canonical", "v1.2.0", NOW, NOW, NOW));

        GraphIdentity identity = new CanonicalGraphMetadataBootstrap(
                store, new FixedClient(tenant, "v9.9.9"), Clock.fixed(NOW, ZoneOffset.UTC))
                .bootstrap(tenant);

        assertThat(identity.projectionVersion()).isEqualTo("v1.2.0");
        assertThat(readMetadata(tenant).projectionVersion()).isEqualTo("v1.2.0");
    }

    @Test
    void failsClosedWithoutCanonicalEntityData() {
        String tenant = "tenant-metadata-no-data";

        assertThat(store.hasCanonicalData(tenant)).isFalse();
        assertThatThrownBy(() -> new CanonicalGraphMetadataBootstrap(
                store, new FixedClient(tenant, "v1.2.0"), Clock.fixed(NOW, ZoneOffset.UTC))
                .bootstrap(tenant))
                .isInstanceOf(MissingCanonicalGraphException.class);
    }

    private static Callable<ProjectionProvisioningResult> provisionTask(
            CountDownLatch start, String tenant, Instant createdAt) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            return new ProjectionProvisioner(store, new FixedClient(tenant, "v1.2.0"),
                    Clock.fixed(createdAt, ZoneOffset.UTC)).provision(tenant);
        };
    }

    private static Callable<GraphIdentity> bootstrapTask(
            CountDownLatch start, String tenant, String version) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            return new CanonicalGraphMetadataBootstrap(
                    store, new FixedClient(tenant, version), Clock.fixed(NOW, ZoneOffset.UTC))
                    .bootstrap(tenant);
        };
    }

    private static void seedCanonicalEntity(String tenant) {
        cypher.write("CREATE (:Entity {tenant: $tenant, id: 'entity-1', label: 'Entity 1', "
                + "type: 'Concept'})", Map.of("tenant", tenant));
    }

    private static OptionalMetadata readMetadata(String tenant) {
        List<Map<String, Object>> rows = cypher.read("""
                MATCH (m:CanonicalGraphMetadata {tenant_id: $tenant})
                RETURN count(m) AS c, collect(m.projection_version)[0] AS version
                """, Map.of("tenant", tenant));
        Map<String, Object> row = rows.getFirst();
        return new OptionalMetadata(
                ((Number) row.get("c")).intValue(), String.valueOf(row.get("version")));
    }

    private record OptionalMetadata(int count, String projectionVersion) {}

    private record FixedClient(String tenant, String version) implements SyntologyActiveVersionClient {
        @Override
        public ActiveOntologyVersion getActiveVersion(String tenantId) {
            return new ActiveOntologyVersion(tenant, version, "uuid:" + version, NOW);
        }
    }
}
