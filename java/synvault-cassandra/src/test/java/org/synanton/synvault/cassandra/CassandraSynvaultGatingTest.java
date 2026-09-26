package org.synanton.synvault.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.testkit.ConformanceGatingContract;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

/** Gating suite for the Cassandra matrix — including the 008 UNSUPPORTED rows. */
class CassandraSynvaultGatingTest extends ConformanceGatingContract {

    private static CassandraContainer cassandra;
    private static CqlSession session;
    private static IngestionCacheClient client;
    private static CassandraSynvaultStore store;

    @BeforeAll
    static void startCassandra() {
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
        client = new IngestionCacheClient(session);
        store = new CassandraSynvaultStore(client, "gating-" + UUID.randomUUID());
    }

    @AfterAll
    static void stopCassandra() {
        if (session != null) {
            session.close();
        }
        if (cassandra != null) {
            cassandra.stop();
        }
    }

    @Override
    protected Conformant adapter() {
        return store;
    }

    @Override
    protected Map<String, Boolean> claimedFlags() {
        var flags = store.capabilities();
        return Map.of(
                Capabilities.SYNVAULT_REVISION, flags.supportsTransactions(),
                Capabilities.SYNVAULT_DELETE, false,
                Capabilities.SYNVAULT_DOCUMENT, true,
                Capabilities.SYNVAULT_CHUNKS, true,
                Capabilities.SYNVAULT_PROVENANCE, flags.supportsProvenance(),
                Capabilities.SYNVAULT_PAGINATION, flags.supportsCursorPagination(),
                Capabilities.SYNVAULT_OCC, flags.supportsStorageRevisions());
    }
}
