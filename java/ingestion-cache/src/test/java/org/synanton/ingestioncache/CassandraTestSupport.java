package org.synanton.ingestioncache;

import com.datastax.oss.driver.api.core.CqlSession;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Shared Cassandra container and schema for the ingestion-cache test JVM.
 *
 * <p>The Cassandra schema DDL dominates local test time. Start one container per
 * test task, install migrations once, and reset data between test classes.
 *
 * <p><strong>Sequential-class invariant:</strong> the shared keyspace and class-level
 * truncation require one test class at a time. The ingestion-cache test task pins
 * Gradle to one fork and disables JUnit parallel execution. Do not enable parallel
 * test classes or multiple test forks without introducing per-class keyspaces or a
 * stronger isolation mechanism.
 */
public final class CassandraTestSupport {

    private static final CassandraContainer CASSANDRA =
        new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
    private static CqlSession session;
    private static InetSocketAddress contact;
    private static volatile boolean shutdownHookInstalled;

    private CassandraTestSupport() {
    }

    public static synchronized CqlSession session() {
        if (session == null) {
            CASSANDRA.start();
            contact = new InetSocketAddress(
                CASSANDRA.getHost(), CASSANDRA.getMappedPort(9042));
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

    /** A separate session to the shared container, for restart/second-session checks. */
    public static CqlSession newSession() {
        session();
        return CqlSession.builder()
            .addContactPoint(contact)
            .withLocalDatacenter("datacenter1")
            .withKeyspace("ingestion_cache")
            .build();
    }

    /** Truncate all user tables. Call from a class-level setup before tests mutate data. */
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
        }, "ingestion-cache-cassandra-shutdown"));
    }
}
