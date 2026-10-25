package org.synanton.contentcache.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.synanton.contentcache.ContentMetadata;
import org.synanton.storage.contract.TenantScope;

/**
 * Env-gated replica parity (#339). Skipped visibly when no replica is
 * configured; the primary acceptance is separate. Known data must be non-zero
 * on the replica (setter/role work) and an unknown tenant must see zero rows
 * for the same digest (RLS is active, not bypassed).
 */
class ReplicaReadParityTest {

    @Test
    void knownTenantIsVisibleAndUnknownTenantIsIsolatedOnReplica() throws Exception {
        String primaryUrl = System.getenv("REPLICA_PARITY_PRIMARY_URL");
        String replicaUrl = System.getenv("REPLICA_PARITY_REPLICA_URL");
        Assumptions.assumeTrue(
                primaryUrl != null && !primaryUrl.isBlank()
                        && replicaUrl != null && !replicaUrl.isBlank(),
                "replica parity skipped: set REPLICA_PARITY_PRIMARY_URL and REPLICA_PARITY_REPLICA_URL");

        DataSource primary = dataSource(primaryUrl, env("REPLICA_PARITY_PRIMARY_USER", "app"),
                env("REPLICA_PARITY_PRIMARY_PASSWORD", "app"));
        DataSource replica = dataSource(replicaUrl, env("REPLICA_PARITY_REPLICA_USER", "app"),
                env("REPLICA_PARITY_REPLICA_PASSWORD", "app"));

        TenantScope tenant = TenantScope.of("replica-parity-" + UUID.randomUUID());
        TenantScope unknown = TenantScope.of("replica-unknown-" + UUID.randomUUID());
        TenantScopedContentStore primaryStore =
                TenantScopedContentStore.forTenant(primary, 90, tenant);
        String digest = primaryStore.put(
                "replica-parity".getBytes(StandardCharsets.UTF_8),
                ContentMetadata.of("text/plain", "owner-1")).reference().digest();

        TenantScopedContentStore replicaStore =
                TenantScopedContentStore.forTenant(replica, 90, tenant);
        int replicaCount = waitForReplica(replicaStore, digest, Duration.ofSeconds(30));

        assertThat(replicaCount)
                .as("known tenant is non-zero on the replica; a missing setter would be zero")
                .isEqualTo(1);
        assertThat(TenantScopedContentStore.forTenant(replica, 90, unknown).countByDigest(digest))
                .as("unknown tenant sees zero on the replica; a bypass role would see the row")
                .isZero();
        assertThat(primaryStore.countByDigest(digest))
                .as("primary/replica single-tenant read parity")
                .isEqualTo(replicaCount);
    }

    private static int waitForReplica(TenantScopedContentStore store, String digest, Duration timeout)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        int count = 0;
        while (Instant.now().isBefore(deadline)) {
            count = store.countByDigest(digest);
            if (count == 1) {
                return count;
            }
            Thread.sleep(500);
        }
        return count;
    }

    private static DataSource dataSource(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
