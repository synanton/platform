package org.synanton.contentcache.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.synanton.contentcache.ContentMetadata;
import org.synanton.contentcache.ContentNotFoundException;
import org.synanton.contentcache.ContentReference;
import org.synanton.contentcache.ContentStoreException;
import org.synanton.contentcache.PutResult;

class JdbcContentStoreTest extends PostgresTestBase {

    private static DataSource dataSource;
    private JdbcContentStore store;

    @BeforeAll
    static void start() throws Exception {
        ensureStarted();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl());
        ds.setUser("app");
        ds.setPassword("app");
        dataSource = ds;
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection admin = adminConnection();
                var stmt = admin.createStatement()) {
            stmt.execute("TRUNCATE audit_log, reference_registry, retention_policies, artifacts");
        }
        store = new JdbcContentStore(dataSource, 90);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String tenant() {
        return "t-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void putGetRoundTrip() {
        PutResult res = store.put(tenant(), bytes("hello content-cache"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(res.dedupHit()).isFalse();
        assertThat(store.get(res.reference())).isEqualTo(bytes("hello content-cache"));
    }

    @Test
    void sameTenantDedupHits() {
        String tenant = tenant();
        PutResult first = store.put(tenant, bytes("dedup me"), ContentMetadata.of("text/plain", "owner-1"));
        PutResult second = store.put(tenant, bytes("dedup me"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(second.dedupHit()).isTrue();
        assertThat(second.reference().artifactId()).isEqualTo(first.reference().artifactId());
    }

    @Test
    void sameBytesDifferentTenantsAreIsolated() {
        PutResult a = store.put(tenant(), bytes("shared"), ContentMetadata.of("text/plain", "owner-1"));
        PutResult b = store.put(tenant(), bytes("shared"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(b.reference().artifactId()).isNotEqualTo(a.reference().artifactId());
        assertThat(b.dedupHit()).isFalse();
    }

    @Test
    void crossTenantGetIsIndistinguishableNotFound() {
        String tenantA = tenant();
        PutResult res = store.put(tenantA, bytes("secret"), ContentMetadata.of("text/plain", "owner-1"));
        ContentReference foreign = new ContentReference(
                tenant(), res.reference().artifactId(), res.reference().digest(), 1, 1);
        assertThatThrownBy(() -> store.get(foreign)).isInstanceOf(ContentNotFoundException.class);
        assertThat(store.exists(foreign)).isFalse();
    }

    @Test
    void legalHoldRequiresReasonAndAudits() throws Exception {
        String tenant = tenant();
        PutResult res = store.put(tenant, bytes("held"), ContentMetadata.of("text/plain", "owner-1"));
        assertThatThrownBy(() -> store.setLegalHold(res.reference(), true, "admin-1", ""))
                .isInstanceOf(ContentStoreException.class);
        store.setLegalHold(res.reference(), true, "admin-1", "litigation hold");
        try (Connection con = adminConnection();
                var ps = con.prepareStatement(
                        "SELECT legal_hold FROM retention_policies WHERE tenant_id = ? AND artifact_id = ?::uuid LIMIT 1")) {
            ps.setString(1, tenant);
            ps.setString(2, res.reference().artifactId());
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }
        try (Connection con = adminConnection();
                var ps = con.prepareStatement(
                        "SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'legal_hold_place'")) {
            ps.setString(1, tenant);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
            }
        }
    }

    @Test
    void registerReleaseReferenceLifecycle() throws Exception {
        String tenant = tenant();
        PutResult res = store.put(tenant, bytes("registry"), ContentMetadata.of("text/plain", "owner-1"));
        String digest = res.reference().digest();
        store.registerReference(tenant, digest, "owner-2");
        store.releaseReference(tenant, digest, "owner-2");
        try (Connection con = adminConnection();
                var ps = con.prepareStatement(
                        "SELECT released_at IS NOT NULL FROM reference_registry"
                                + " WHERE tenant_id = ? AND digest = ? AND owner_id = ?")) {
            ps.setString(1, tenant);
            ps.setString(2, digest);
            ps.setString(3, "owner-2");
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }
    }

    @Test
    void setRetentionUpdatesWindow() {
        String tenant = tenant();
        PutResult res = store.put(tenant, bytes("retention"), ContentMetadata.of("text/plain", "owner-1"));
        store.setRetention(res.reference(), 60);
    }

    @Test
    void rejectsEmptyPayloadAndMissingTenant() {
        assertThatThrownBy(() -> store.put(tenant(), new byte[0], ContentMetadata.of("text/plain", "o")))
                .isInstanceOf(ContentStoreException.class);
        assertThatThrownBy(() -> store.put("", bytes("x"), ContentMetadata.of("text/plain", "o")))
                .isInstanceOf(ContentStoreException.class);
    }

    @Test
    void rawQueryWithoutTenantContextFailsClosed() throws Exception {
        String tenant = tenant();
        store.put(tenant, bytes("unscoped"), ContentMetadata.of("text/plain", "owner-1"));
        try (Connection con = dataSource.getConnection()) {
            assertThat(count(con, "SELECT count(*) FROM artifacts")).as("unscoped sees nothing").isZero();
        }
    }

    @Test
    void rawQueryUsesTransactionScopedTenantNotExplicitFilter() throws Exception {
        String tenantA = tenant();
        String tenantB = tenant();
        store.put(tenantA, bytes("a"), ContentMetadata.of("text/plain", "owner-1"));
        store.put(tenantB, bytes("b"), ContentMetadata.of("text/plain", "owner-1"));
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            setTenant(con, tenantA);
            assertThat(count(con, "SELECT count(*) FROM artifacts")).as("A sees own row").isEqualTo(1);
            assertThat(count(con, "SELECT count(*) FROM artifacts WHERE tenant_id = '" + tenantB + "'"))
                    .as("explicit cross-tenant filter is still scoped").isZero();
            con.rollback();
        }
    }

    @Test
    void rawQueryWithEmptyTenantContextFailsClosed() throws Exception {
        String tenant = tenant();
        store.put(tenant, bytes("empty-scope"), ContentMetadata.of("text/plain", "owner-1"));
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            setTenant(con, "");
            assertThat(count(con, "SELECT count(*) FROM artifacts"))
                    .as("empty scope matches no rows").isZero();
            con.rollback();
        }
    }

    @Test
    void crossTenantRawWriteAffectsNothing() throws Exception {
        String tenantA = tenant();
        String tenantB = tenant();
        store.put(tenantB, bytes("victim"), ContentMetadata.of("text/plain", "owner-1"));
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            setTenant(con, tenantA);
            int deleted;
            try (var ps = con.prepareStatement("DELETE FROM artifacts WHERE tenant_id = '" + tenantB + "'")) {
                deleted = ps.executeUpdate();
            }
            assertThat(deleted).as("A cannot delete B").isZero();
            con.rollback();
        }
        try (Connection con = adminConnection()) {
            assertThat(count(con, "SELECT count(*) FROM artifacts WHERE tenant_id = '" + tenantB + "'"))
                    .as("B row survives").isEqualTo(1);
        }
    }

    private static void setTenant(Connection con, String tenant) throws Exception {
        try (var ps = con.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenant);
            ps.execute();
        }
    }

    private static int count(Connection con, String sql) throws Exception {
        try (var ps = con.prepareStatement(sql); var rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
