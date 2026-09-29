package org.synanton.synvault.postgres;

import java.sql.Connection;
import org.junit.jupiter.api.BeforeAll;
import org.synanton.storage.testkit.SynvaultStoreContract;
import org.synanton.synvault.api.SynvaultStore;

/**
 * {@link SynvaultStoreContract} against live Postgres (PG-POC-004).
 * Per-test isolation via full truncate (fixed contract tenants): the
 * container's superuser truncates, bypassing RLS without touching policies.
 */
class PostgresSynvaultStoreTest extends SynvaultStoreContract {

    @BeforeAll
    static void ensureSchema() {
        PostgresTestBase.ensureStarted();
    }

    private static void truncateAll() {
        try (Connection admin = PostgresTestBase.adminConnection();
                var stmt = admin.createStatement()) {
            stmt.execute("TRUNCATE documents, chunks, provenance, publication_log");
        } catch (Exception e) {
            throw new IllegalStateException("truncate failed", e);
        }
    }

    @Override
    protected SynvaultStore newStore() {
        truncateAll();
        try {
            org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
            try (Connection probe = PostgresTestBase.connection()) {
                ds.setUrl(probe.getMetaData().getURL());
            }
            ds.setUser("app");
            ds.setPassword("app");
            return new PostgresSynvaultStore(ds);
        } catch (Exception e) {
            throw new IllegalStateException("datasource setup failed", e);
        }
    }

    /** And a DataSource built the straightforward way (documents the production shape). */
    static javax.sql.DataSource dataSource(String url, String user, String password) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }
}
