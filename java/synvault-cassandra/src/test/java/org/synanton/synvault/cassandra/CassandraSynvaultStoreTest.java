package org.synanton.synvault.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.storage.testkit.SynvaultStoreContract;
import org.synanton.synvault.api.SynvaultStore;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link SynvaultStoreContract} against a real Cassandra (testcontainers, image cached
 * locally). Each test gets an isolated store via a unique namespace — no schema churn.
 */
class CassandraSynvaultStoreTest extends SynvaultStoreContract {

    private static CassandraContainer cassandra;
    private static CqlSession session;
    private static IngestionCacheClient client;

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
        CqlSession session =
                CqlSession.builder()
                        .addContactPoint(contact)
                        .withLocalDatacenter("datacenter1")
                        .withKeyspace("ingestion_cache")
                        .build();
        CassandraSynvaultStoreTest.session = session;
        SchemaInstaller.install(session);
        client = new IngestionCacheClient(session);
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
    protected SynvaultStore newStore() {
        return new CassandraSynvaultStore(client, "test-" + UUID.randomUUID());
    }
}
