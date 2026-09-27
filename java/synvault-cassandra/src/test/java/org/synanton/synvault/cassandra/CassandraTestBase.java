package org.synanton.synvault.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared live-Cassandra fixture (live-test policy: one container per JVM so the
 * suite stays PR-suitable). Test isolation comes from per-store namespaces, not
 * schema churn. Stopped via shutdown hook; Ryuk reaps leftovers on JVM exit.
 */
abstract class CassandraTestBase {

    private static volatile boolean started;
    private static CassandraContainer container;
    private static CqlSession session;
    protected static IngestionCacheClient client;

    static {
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    if (session != null) {
                                        session.close();
                                    }
                                    if (container != null) {
                                        container.stop();
                                    }
                                }));
    }

    protected static synchronized void ensureStarted() {
        if (started) {
            return;
        }
        container = new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
        container.start();
        InetSocketAddress contact =
                new InetSocketAddress(container.getHost(), container.getMappedPort(9042));
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
        started = true;
    }
}
