package org.synanton.relix;

import com.datastax.oss.driver.api.core.CqlSession;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Shared Cassandra fixture for relix integration tests.
 *
 * <p>One container and one migration install per test task. Tables are truncated
 * before each migrated class, so the test task must run classes sequentially.
 * Neo4j lifecycles remain owned by each test class.
 */
public final class RelixCassandraTestSupport {

    private static final CassandraContainer CASSANDRA =
        new CassandraContainer(DockerImageName.parse("cassandra:4.1.12"));
    private static CqlSession session;
    private static InetSocketAddress contact;
    private static volatile boolean shutdownHookInstalled;

    private RelixCassandraTestSupport() {
    }

    public static synchronized CqlSession session() {
        if (session == null) {
            CASSANDRA.start();
            contact = new InetSocketAddress(CASSANDRA.getHost(), CASSANDRA.getMappedPort(9042));
            try (CqlSession admin = CqlSession.builder()
                    .addContactPoint(contact)
                    .withLocalDatacenter("datacenter1")
                    .build()) {
                admin.execute("CREATE KEYSPACE IF NOT EXISTS ingestion_cache WITH replication = "
                    + "{'class':'SimpleStrategy','replication_factor':1}");
            }
            CqlSession initialised = CqlSession.builder()
                .addContactPoint(contact)
                .withLocalDatacenter("datacenter1")
                .withKeyspace("ingestion_cache")
                .build();
            try {
                SchemaInstaller.install(initialised);
            } catch (RuntimeException e) {
                initialised.close();
                throw e;
            }
            session = initialised;
            installShutdownHook();
        }
        return session;
    }

    public static CqlSession newSession() {
        session();
        return CqlSession.builder()
            .addContactPoint(contact)
            .withLocalDatacenter("datacenter1")
            .withKeyspace("ingestion_cache")
            .build();
    }

    public static synchronized void reset() {
        CqlSession current = session();
        List<String> tables = current.execute(
                "SELECT table_name FROM system_schema.tables WHERE keyspace_name='ingestion_cache'")
            .all().stream()
            .map(row -> row.getString("table_name"))
            .sorted()
            .toList();
        for (String table : tables) {
            current.execute("TRUNCATE ingestion_cache." + table);
        }
    }

    private static void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (session != null) {
                session.close();
            }
            if (CASSANDRA.isRunning()) {
                CASSANDRA.stop();
            }
        }, "relix-cassandra-shutdown"));
    }
}
