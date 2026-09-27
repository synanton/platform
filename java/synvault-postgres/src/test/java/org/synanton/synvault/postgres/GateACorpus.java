package org.synanton.synvault.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * PG-POC-005 Gate A corpus seeder (raw SQL, no adapter — the probe tests the
 * server, not an abstraction over it).
 *
 * <p>Frozen probe query (selectivity 0.1%, the hardest case):
 *
 * <pre>
 * SELECT chunk_id FROM chunks ORDER BY embedding &lt;-&gt; '[1,0,...,0]' LIMIT 10
 * </pre>
 *
 * <p>as the small tenant. Corpus shape (adversarial by construction):
 *
 * <ul>
 *   <li>{@code tenant_big}: 20,000 rows clustered near the query point (distance
 *       &lt; ~2). Unfiltered top-10 is entirely cross-tenant.
 *   <li>{@code tenant_small}: 20 rows (~0.1%) at distance exactly 10.
 * </ul>
 *
 * <p>If RLS composes pre-ranking, the small tenant sees its own 10 rows. If RLS
 * filters post-ANN, the ANN returns 10 cross-tenant neighbours, RLS removes
 * them all, and the result is empty — the failure mode is visible, not masked.
 *
 * <p>Second frozen leg (same query, large eligible share): {@code tenant_half}
 * owns ~10,000 near rows (~33% selectivity). At this share a brute-force sort
 * is expensive, so the planner must choose between the HNSW index and a scan —
 * whichever it chooses, the verbatim plan answers whether RLS sits inside the
 * ANN node or after it.
 */
final class GateACorpus {

    static final int DIMS = 384;
    static final int BIG_ROWS = 20_000;
    static final int SMALL_ROWS = 20;
    static final int HALF_ROWS = 10_000;
    static final int LIMIT = 10;

    /** Frozen query vector: e1 (1.0 in dim 0, 0.0 elsewhere). */
    static String queryVector() {
        StringBuilder sb = new StringBuilder("[");
        sb.append("1");
        for (int i = 1; i < DIMS; i++) {
            sb.append(",0");
        }
        return sb.append("]").toString();
    }

    /** Near vector: e1 plus small Gaussian noise (distance &lt; ~2). */
    static String nearVector(Random random) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < DIMS; i++) {
            double v = (i == 0 ? 1.0 : 0.0) + random.nextGaussian() * 0.05;
            if (i > 0) {
                sb.append(",");
            }
            sb.append(String.format(Locale.ROOT, "%.6f", v));
        }
        return sb.append("]").toString();
    }

    /** Far vector: e1 shifted by exactly 10.0 in dim 1 (distance 10). */
    static String farVector() {
        StringBuilder sb = new StringBuilder("[1,10");
        for (int i = 2; i < DIMS; i++) {
            sb.append(",0");
        }
        return sb.append("]").toString();
    }

    record Seeded(UUID tenantBig, UUID tenantSmall, UUID tenantHalf) {}

    private static void seedBatch(
            PreparedStatement ps, UUID tenant, UUID doc, int rows, String[] vectors, int startOrdinal)
            throws Exception {
        for (int i = 0; i < rows; i++) {
            ps.setObject(1, tenant);
            ps.setObject(2, UUID.randomUUID());
            ps.setObject(3, doc);
            ps.setInt(4, startOrdinal + i);
            ps.setString(5, "row-" + startOrdinal + "-" + i);
            ps.setInt(6, 8);
            ps.setString(7, vectors[i % vectors.length]);
            ps.addBatch();
            if (i % 2_000 == 0) {
                ps.executeBatch();
            }
        }
        ps.executeBatch();
    }

    static Seeded seed(Connection admin) throws Exception {
        UUID tenantBig = UUID.randomUUID();
        UUID tenantSmall = UUID.randomUUID();
        UUID tenantHalf = UUID.randomUUID();
        Random random = new Random(0xC0FFEE);
        String far = farVector();
        try (PreparedStatement ps =
                admin.prepareStatement(
                        "INSERT INTO chunks (tenant_id, chunk_id, doc_id, ordinal, text,"
                                + " token_count, embedding) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS vector))")) {
            // tenant_big: 20,000 near rows (cross-tenant neighbours for leg 1).
            String[] nearBatch = new String[2_000];
            for (int i = 0; i < nearBatch.length; i++) {
                nearBatch[i] = nearVector(random);
            }
            for (int b = 0; b < 10; b++) {
                seedBatch(ps, tenantBig, UUID.randomUUID(), 2_000, nearBatch, b * 2_000);
                for (int i = 0; i < nearBatch.length; i++) {
                    nearBatch[i] = nearVector(random);
                }
            }
            // tenant_small: 20 far rows (~0.1% selectivity, leg 1).
            String[] farBatch = new String[] {far};
            seedBatch(ps, tenantSmall, UUID.randomUUID(), SMALL_ROWS, farBatch, 0);
            // tenant_half: 10,000 near rows (~33% selectivity, leg 2).
            for (int b = 0; b < 5; b++) {
                seedBatch(ps, tenantHalf, UUID.randomUUID(), 2_000, nearBatch, b * 2_000);
                for (int i = 0; i < nearBatch.length; i++) {
                    nearBatch[i] = nearVector(random);
                }
            }
        }
        return new Seeded(tenantBig, tenantSmall, tenantHalf);
    }

    /** Synchronous cleanup (lifecycle rule): probe rows never accumulate. */
    static void remove(Connection admin, Seeded seeded) throws Exception {
        // Admin (superuser) bypasses RLS: deterministic full cleanup.
        try (Statement stmt = admin.createStatement()) {
            stmt.execute(
                    "DELETE FROM chunks WHERE tenant_id IN ('"
                            + seeded.tenantBig()
                            + "', '"
                            + seeded.tenantSmall()
                            + "', '"
                            + seeded.tenantHalf()
                            + "')");
        }
    }
}
