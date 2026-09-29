package org.synanton.synvault.postgres;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG-POC-003 permanent guard (mirrors the ingestion-cache SchemaInventoryTest
 * that caught the annotations-table skip): every table, index, RLS flag, and
 * policy the canonical DDL declares must actually exist after install. A
 * schema that "looks right" while RLS is silently off is the same failure
 * class — hence the pg_tables/pg_policies assertions, not just table names.
 */
class SchemaInventoryTest extends PostgresTestBase {

    private static final List<String> EXPECTED_TABLES =
            List.of("documents", "chunks", "provenance", "publication_log");

    private static final List<String> EXPECTED_CHUNK_INDEXES =
            List.of(
                    "chunks_embedding_hnsw",
                    "chunks_tsv_gin",
                    "chunks_tenant_doc_btree",
                    "chunks_metadata_gin");

    @BeforeAll
    static void startInfra() {
        ensureStarted();
    }

    @AfterAll
    static void stopInfra() {
        // Container lifecycle is per-JVM via PostgresTestBase (shutdown hook);
        // connections are per-test try-with-resources (synchronous teardown).
    }

    @Test
    void allCanonicalTablesExist() throws Exception {
        Set<String> actual = new HashSet<>();
        try (Connection conn = connection();
                ResultSet rs =
                        conn.createStatement()
                                .executeQuery(
                                        "SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
            while (rs.next()) {
                actual.add(rs.getString(1));
            }
        }
        assertThat(actual).containsAll(EXPECTED_TABLES);
    }

    @Test
    void rowSecurityEnabledOnAllTables() throws Exception {
        Map<String, Boolean> flags = new HashMap<>();
        try (Connection conn = connection();
                ResultSet rs =
                        conn.createStatement()
                                .executeQuery(
                                        "SELECT tablename, rowsecurity FROM pg_tables"
                                                + " WHERE schemaname = 'public'")) {
            while (rs.next()) {
                flags.put(rs.getString(1), rs.getBoolean(2));
            }
        }
        for (String table : EXPECTED_TABLES) {
            assertThat(flags)
                    .as("rowsecurity flag for " + table)
                    .containsEntry(table, true);
        }
    }

    @Test
    void tenantIsolationPolicyPresentOnAllTables() throws Exception {
        Map<String, String> policies = new HashMap<>();
        try (Connection conn = connection();
                ResultSet rs =
                        conn.createStatement()
                                .executeQuery(
                                        "SELECT tablename, policyname FROM pg_policies"
                                                + " WHERE schemaname = 'public'")) {
            while (rs.next()) {
                policies.put(rs.getString(1), rs.getString(2));
            }
        }
        for (String table : EXPECTED_TABLES) {
            assertThat(policies)
                    .as("tenant_isolation policy for " + table)
                    .containsEntry(table, "tenant_isolation");
        }
    }

    @Test
    void chunkIndexesExistWithHnswAccessMethod() throws Exception {
        Set<String> actual = new HashSet<>();
        try (Connection conn = connection();
                ResultSet rs =
                        conn.createStatement()
                                .executeQuery(
                                        "SELECT indexname FROM pg_indexes WHERE tablename = 'chunks'")) {
            while (rs.next()) {
                actual.add(rs.getString(1));
            }
        }
        assertThat(actual).containsAll(EXPECTED_CHUNK_INDEXES);

        String accessMethod;
        try (Connection conn = connection();
                ResultSet rs =
                        conn.createStatement()
                                .executeQuery(
                                        "SELECT am.amname FROM pg_index i"
                                                + " JOIN pg_class c ON c.oid = i.indexrelid"
                                                + " JOIN pg_am am ON am.oid = c.relam"
                                                + " WHERE c.relname = 'chunks_embedding_hnsw'")) {
            assertThat(rs.next()).as("hnsw index present in pg_index").isTrue();
            accessMethod = rs.getString(1);
        }
        assertThat(accessMethod).isEqualTo("hnsw");
    }

    @Test
    void vectorExtensionInstalled() throws Exception {        Set<String> extensions = new HashSet<>();
        try (Connection conn = connection();
                ResultSet rs =
                        conn.createStatement().executeQuery("SELECT extname FROM pg_extension")) {
            while (rs.next()) {
                extensions.add(rs.getString(1));
            }
        }
        assertThat(extensions).contains("vector");
    }

    @Test
    void identityColumnsAreTextNotUuid() throws Exception {
        // PG-POC-004 finding, pinned: domain ids (tenant_a, d1) are opaque
        // strings, not UUID-shaped — uuid columns reject them at insert. A
        // future schema edit that reverts to uuid must fail here, loudly.
        Map<String, String> types = new HashMap<>();
        try (Connection conn = connection();
                ResultSet rs =
                        conn.createStatement()
                                .executeQuery(
                                        "SELECT table_name || '.' || column_name, data_type"
                                                + " FROM information_schema.columns"
                                                + " WHERE table_schema = 'public'"
                                                + " AND column_name IN"
                                                + " ('tenant_id', 'doc_id', 'chunk_id')")) {
            while (rs.next()) {
                types.put(rs.getString(1), rs.getString(2));
            }
        }
        assertThat(types).isNotEmpty();
        for (Map.Entry<String, String> entry : types.entrySet()) {
            assertThat(entry.getValue())
                    .as("identity column " + entry.getKey())
                    .isEqualTo("text");
        }
    }
}
