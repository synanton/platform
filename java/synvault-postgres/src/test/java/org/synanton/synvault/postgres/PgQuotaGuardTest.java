package org.synanton.synvault.postgres;

import java.sql.Connection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PG-POC-012 permanent quota guard: the PG-shaped analogue of YDB's path-count
 * guard. PG's silently-accumulating resources are backend connections
 * ({@code max_connections} defaults to 100; every adapter op and every test
 * handle borrows one) and transactions left {@code idle in transaction}
 * (hold locks, block vacuum, bloat tables). Both fail loudly here while the
 * count is actionable — not at 100 when the suite starts flaking.
 *
 * <p>Skips (visibly) when the container is unreachable — the guard targets the
 * PoC container, not arbitrary deployments.
 */
class PgQuotaGuardTest extends PostgresTestBase {

    /** Well below max_connections=100; the suite should hold a handful. */
    private static final long CONNECTION_THRESHOLD = 20;

    @Test
    void backendConnectionCountBelowThreshold() {
        ensureStarted();
        long count;
        try (Connection admin = PostgresTestBase.adminConnection();
                var ps =
                        admin.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE datname = current_database()"
                                        + " AND pid <> pg_backend_pid()")) {
            try (var rs = ps.executeQuery()) {
                rs.next();
                count = rs.getLong(1);
            }
        } catch (Exception e) {
            assumeTrue(false, "container unreachable, quota guard skipped: " + e.getMessage());
            return;
        }
        assertThat(count)
                .as("backend connections approaching max_connections — close leaked handles")
                .isLessThan(CONNECTION_THRESHOLD);
    }

    @Test
    void noStaleIdleInTransactionSessions() {
        ensureStarted();
        long stale;
        try (Connection admin = PostgresTestBase.adminConnection();
                var ps =
                        admin.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE datname = current_database()"
                                        + " AND pid <> pg_backend_pid()"
                                        + " AND state = 'idle in transaction'"
                                        + " AND now() - state_change > interval '60 seconds'")) {
            try (var rs = ps.executeQuery()) {
                rs.next();
                stale = rs.getLong(1);
            }
        } catch (Exception e) {
            assumeTrue(false, "container unreachable, quota guard skipped: " + e.getMessage());
            return;
        }
        assertThat(stale)
                .as("stale idle-in-transaction sessions hold locks and block vacuum")
                .isZero();
    }
}
