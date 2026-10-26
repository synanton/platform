package org.synanton.contentcache.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.synanton.contentcache.ContentMetadata;
import org.synanton.storage.contract.TenantScope;

/**
 * Replica parity (#339/#428). Requires a primary and streaming replica; fails
 * rather than skips when either DSN is absent. Known data must be non-zero on
 * the replica (setter/role work) and an unknown tenant must see zero rows for
 * the same digest (RLS is active, not bypassed).
 */
class ReplicaReadParityTest {

    @Test
    void knownTenantIsVisibleAndUnknownTenantIsIsolatedOnReplica() throws Exception {
        String primaryUrl = System.getenv("REPLICA_PARITY_PRIMARY_URL");
        String replicaUrl = System.getenv("REPLICA_PARITY_REPLICA_URL");
        if (primaryUrl == null || primaryUrl.isBlank()
                || replicaUrl == null || replicaUrl.isBlank()) {
            throw new AssertionError(
                    "replica parity requires REPLICA_PARITY_PRIMARY_URL and REPLICA_PARITY_REPLICA_URL");
        }

        DataSource primary = dataSource(primaryUrl, env("REPLICA_PARITY_PRIMARY_USER", "app"),
                env("REPLICA_PARITY_PRIMARY_PASSWORD", "app"));
        DataSource replica = dataSource(replicaUrl, env("REPLICA_PARITY_REPLICA_USER", "app"),
                env("REPLICA_PARITY_REPLICA_PASSWORD", "app"));

        TenantScope tenantA = TenantScope.of("replica-parity-a-" + UUID.randomUUID());
        TenantScope tenantB = TenantScope.of("replica-parity-b-" + UUID.randomUUID());
        TenantScope unknown = TenantScope.of("replica-unknown-" + UUID.randomUUID());
        TenantScopedContentStore primaryA =
                TenantScopedContentStore.forTenant(primary, 90, tenantA);
        TenantScopedContentStore primaryB =
                TenantScopedContentStore.forTenant(primary, 90, tenantB);
        String digestA = primaryA.put(
                "replica-parity-a".getBytes(StandardCharsets.UTF_8),
                ContentMetadata.of("text/plain", "owner-1")).reference().digest();
        String digestB = primaryB.put(
                "replica-parity-b".getBytes(StandardCharsets.UTF_8),
                ContentMetadata.of("text/plain", "owner-1")).reference().digest();

        TenantScopedContentStore replicaA =
                TenantScopedContentStore.forTenant(replica, 90, tenantA);
        TenantScopedContentStore replicaB =
                TenantScopedContentStore.forTenant(replica, 90, tenantB);
        int replicaCountA = waitForReplica(replicaA, digestA, Duration.ofSeconds(30));
        int replicaCountB = waitForReplica(replicaB, digestB, Duration.ofSeconds(30));

        assertThat(replicaCountA)
                .as("known tenant A is non-zero on the replica; a missing setter would be zero")
                .isEqualTo(1);
        assertThat(replicaCountB)
                .as("known tenant B is non-zero on the replica")
                .isEqualTo(1);
        assertThat(TenantScopedContentStore.forTenant(replica, 90, unknown).countByDigest(digestA))
                .as("unknown tenant sees zero on the replica; a bypass role would see the row")
                .isZero();

        // Read-layer cross-tenant set: each requested tenant reads only its own
        // artifact. The audited reconciler stays on the primary because a
        // read-only replica cannot accept the same-transaction audit write.
        assertThat(replicaA.countByDigest(digestA)).isEqualTo(1);
        assertThat(replicaA.countByDigest(digestB)).as("A must not see B's artifact").isZero();
        assertThat(replicaB.countByDigest(digestB)).isEqualTo(1);
        assertThat(replicaB.countByDigest(digestA)).as("B must not see A's artifact").isZero();

        assertThat(primaryA.countByDigest(digestA))
                .as("primary/replica A read parity")
                .isEqualTo(replicaCountA);
        assertThat(primaryB.countByDigest(digestB))
                .as("primary/replica B read parity")
                .isEqualTo(replicaCountB);
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
