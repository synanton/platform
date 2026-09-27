package org.synanton.synvault.migrate;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.AnnotationRow;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synvault.cassandra.CassandraSynvaultStore;
import org.synanton.synvault.ydb.YdbSynvaultStore;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 035 migration round-trip: seed Cassandra rows directly (white-box seeding —
 * the port is what's under test, not the seed path), migrate through ports,
 * verify checksums, roll back.
 */
class MigrationRoundTripTest {

    private static final TenantScope TENANT = TenantScope.of("tenant_a");
    private static final PolicyContext POLICY = PolicyContext.of("p", "r1");

    private static CassandraContainer cassandra;
    private static CqlSession session;
    private static IngestionCacheClient cacheClient;
    private static GrpcTransport ydbTransport;
    private static TableClient ydbClient;
    private static String ydbPrefix;

    private static SecurityContext ctx() {
        return SecurityContext.user(TENANT, PrincipalRef.user("u-1"), POLICY);
    }

    @BeforeAll
    static void startInfra() throws Exception {
        cassandra = new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
        cassandra.start();
        InetSocketAddress contact =
                new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042));
        try (CqlSession admin =
                CqlSession.builder()
                        .addContactPoint(contact)
                        .withLocalDatacenter("datacenter1")
                        .build()) {
            admin.execute(
                    "CREATE KEYSPACE IF NOT EXISTS ingestion_cache WITH replication = "
                            + "{'class':'SimpleStrategy','replication_factor':1}");
        }
        session =
                CqlSession.builder()
                        .addContactPoint(contact)
                        .withLocalDatacenter("datacenter1")
                        .withKeyspace("ingestion_cache")
                        .build();
        SchemaInstaller.install(session);
        cacheClient = new IngestionCacheClient(session);

        String caPath = System.getenv().getOrDefault("YDB_CA_PATH", "/tmp/ydb-ca.pem");
        byte[] ca = Files.readAllBytes(Paths.get(caPath));
        ydbTransport =
                GrpcTransport.forConnectionString("grpcs://localhost:2135/local")
                        .withSecureConnection(ca)
                        .build();
        ydbClient = TableClient.newClient(ydbTransport).build();
        ydbPrefix = "t_migrate";
        // Vault schema owned by synvault-ydb main (no local copy — copies drift).
        org.synanton.synvault.ydb.YdbSchema.ensureSchema(ydbClient, ydbPrefix);
        org.synanton.synvault.ydb.YdbSchema.truncateAll(ydbClient, ydbPrefix);
    }

    @AfterAll
    static void stopInfra() {
        if (session != null) {
            session.close();
        }
        if (cassandra != null) {
            cassandra.stop();
        }
        if (ydbClient != null) {
            ydbClient.close();
        }
        if (ydbTransport != null) {
            ydbTransport.close();
        }
    }

    private static UUID ref(String tenant, String docId) {
        return UUID.nameUUIDFromBytes(
                ("synvault-doc:" + tenant + ":" + docId).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void migrateValidateRollback() {
        String tenant = TENANT.tenantId();
        UUID docRef = ref(tenant, "mig-1");
        Instant now = Instant.now();
        cacheClient.upsertManifest(
                new ManifestRow(
                        tenant, docRef, now, 1, "synvault-port/v1", 1, "SYNVAULT", "", "",
                        "cache://mig-1", "", 0L, "application/octet-stream", "", "",
                        "{\"t\":\"Mig Title\",\"m\":{\"k\":\"v\"},\"rev\":0}"));
        for (int i = 0; i < 2; i++) {
            cacheClient.insertChunk(
                    new ChunkRow(tenant, docRef, i, "mig text " + i, "sha" + i));
            cacheClient.insertAnnotation(
                    new AnnotationRow(
                            tenant, "synvault-chunk", "mig-1:o" + i, UUID.randomUUID(),
                            "synvault-provenance", 1, "provenance", "synvault", "provenance",
                            "{\"ex\":\"extractor-1\",\"sv\":\"sv-1\",\"page\":1,\"s\":0,\"e\":6}",
                            "extractor-1", "v1", 1.0, List.of("PUBLIC"), "", "sv-1", null, now, null));
        }

        var source = new CassandraSynvaultStore(cacheClient);
        var target = new YdbSynvaultStore(ydbClient, ydbPrefix, "migtest");
        var migrator = new CassandraToYdbMigrator();

        // Dry run first: checksums computed, nothing written.
        var dry =
                migrator.migrate(
                                new CassandraToYdbMigrator.MigrationPlan(
                                        source, target, ctx(), List.of(DocumentId.of("mig-1")), true))
                        .toCompletableFuture()
                        .join();
        assertThat(dry.allMatch()).isTrue();
        assertThat(target.getDocument(ctx(), DocumentId.of("mig-1")).toCompletableFuture().join())
                .as("dry run writes nothing")
                .isEmpty();

        // Live run + validate + rollback.
        var result =
                migrator.migrate(
                                new CassandraToYdbMigrator.MigrationPlan(
                                        source, target, ctx(), List.of(DocumentId.of("mig-1")), false))
                        .toCompletableFuture()
                        .join();
        assertThat(result.allMatch()).isTrue();
        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).sourceChecksum())
                .isEqualTo(result.documents().get(0).targetChecksum());
        assertThat(
                        target.getChunks(
                                        ctx(), DocumentId.of("mig-1"),
                                        new org.synanton.synvault.api.ChunkQuery(Map.of()),
                                        new org.synanton.storage.contract.PageRequest(
                                                10, java.util.Optional.empty(), true))
                                .toCompletableFuture()
                                .join()
                                .items())
                .hasSize(2);

        migrator.rollback(target, ctx(), List.of(DocumentId.of("mig-1")))
                .toCompletableFuture()
                .join();
        assertThat(target.getDocument(ctx(), DocumentId.of("mig-1")).toCompletableFuture().join())
                .as("rollback removes migrated documents")
                .isEmpty();
    }
}
