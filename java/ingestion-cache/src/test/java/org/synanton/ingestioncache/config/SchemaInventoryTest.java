package org.synanton.ingestioncache.config;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 035 class audit, permanent guard: every table the migrations declare must
 * actually exist after install. Catches silent migration skips (e.g. the V7
 * header-comment semicolon that suppressed the annotations table everywhere).
 */
class SchemaInventoryTest {

    private static final List<String> EXPECTED_TABLES =
            List.of(
                    "analysis_cache",
                    "annotations",
                    "chunks_payload",
                    "embedding_content_cache",
                    "image_caption_cache",
                    "jobs",
                    "manifest",
                    "manifest_transitions_outbox");

    private static CassandraContainer cassandra;
    private static CqlSession session;

    @BeforeAll
    static void startInfra() {
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
    }

    @AfterAll
    static void stopInfra() {
        if (session != null) {
            session.close();
        }
        if (cassandra != null) {
            cassandra.stop();
        }
    }

    @Test
    void allMigrationTablesExist() {
        List<String> actual =
                session
                        .execute(
                                "SELECT table_name FROM system_schema.tables"
                                        + " WHERE keyspace_name='ingestion_cache'")
                        .all()
                        .stream()
                        .map(row -> row.getString("table_name"))
                        .sorted()
                        .collect(Collectors.toList());
        assertThat(actual).containsAll(EXPECTED_TABLES);
    }
}
