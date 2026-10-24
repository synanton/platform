package org.synanton.contentcache.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
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
import org.synanton.contentcache.PutResult;
import org.synanton.storage.contract.TenantScope;

/**
 * #339 facade contract: a scope is required to call the store, and a reference
 * from another tenant is indistinguishable from missing.
 */
class TenantScopedContentStoreTest extends PostgresTestBase {

    private static DataSource dataSource;
    private TenantScopedContentStore tenantA;
    private TenantScopedContentStore tenantB;

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
        tenantA = TenantScopedContentStore.forTenant(dataSource, 90, TenantScope.of("tenant-a-" + UUID.randomUUID()));
        tenantB = TenantScopedContentStore.forTenant(dataSource, 90, TenantScope.of("tenant-b-" + UUID.randomUUID()));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void scopeIsARequiredConstructorValue() {
        assertThatThrownBy(() -> TenantScopedContentStore.forTenant(dataSource, 90, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scope");
        assertThatThrownBy(() -> TenantScope.of(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void rawRepositoryCannotBeConstructedOutsideTheFacade() {
        assertThat(Modifier.isPublic(JdbcContentStore.class.getModifiers()))
                .as("JdbcContentStore must stay package-private; use TenantScopedContentStore")
                .isFalse();
    }

    @Test
    void scopeBWritesAndReadsOnlyScopeBRows() {
        PutResult b = tenantB.put(bytes("b-only"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(tenantB.get(b.reference())).isEqualTo(bytes("b-only"));
        assertThat(tenantA.exists(b.reference())).as("A cannot see B").isFalse();
        assertThatThrownBy(() -> tenantA.get(b.reference()))
                .as("A gets the same not-found as an absent artifact")
                .isInstanceOf(ContentNotFoundException.class);
    }

    @Test
    void scopeAReferenceUnderScopeBIsNotFound() {
        PutResult a = tenantA.put(bytes("a-only"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(tenantB.exists(a.reference())).isFalse();
        assertThatThrownBy(() -> tenantB.get(a.reference()))
                .isInstanceOf(ContentNotFoundException.class);
        assertThatThrownBy(() -> tenantB.setRetention(a.reference(), 30))
                .isInstanceOf(ContentNotFoundException.class);
        assertThatThrownBy(() -> tenantB.markForDeletion(a.reference(), "owner-1"))
                .isInstanceOf(ContentNotFoundException.class);
    }

    @Test
    void sameBytesInDifferentScopesAreDistinctArtifacts() {
        PutResult a = tenantA.put(bytes("shared"), ContentMetadata.of("text/plain", "owner-1"));
        PutResult b = tenantB.put(bytes("shared"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(a.reference().artifactId()).isNotEqualTo(b.reference().artifactId());
        assertThat(a.dedupHit()).isFalse();
        assertThat(b.dedupHit()).isFalse();
    }
}
