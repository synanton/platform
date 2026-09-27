package org.synanton.bench.corpus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 028a.2 acceptance: stateless dealing with Zipf teeth — the histogram shape
 * must match w(i)=1/(i+1) within tolerance, not merely run. Deterministic, so
 * this never flakes: a red test means the hash is wrong, not the seed.
 */
class TenantDealingTest {

    private static final int DOCS = 20_000;
    private static final double REL_TOLERANCE = 0.25;

    @Test
    void stableIdsAndRange() {
        TenantDealing dealing = new TenantDealing();
        assertThat(TenantDealing.tenantId(7)).isEqualTo("tenant_07");
        assertThat(TenantDealing.tenantId(49)).isEqualTo("tenant_49");
        for (long d = 0; d < 1_000; d++) {
            int t = dealing.tenant(d);
            assertThat(t).isBetween(0, 49);
            // Statelessness: same input, same output, no sequence dependence.
            assertThat(dealing.tenant(d)).isEqualTo(t);
        }
    }

    @Test
    void distributionMatchesZipfWithinTolerance() {
        TenantDealing dealing = new TenantDealing();
        int[] counts = new int[TenantDealing.TENANTS];
        for (long d = 0; d < DOCS; d++) {
            counts[dealing.tenant(d)]++;
        }
        int total = 0;
        double maxDeviation = 0.0;
        StringBuilder histogram = new StringBuilder("\ntenant expected actual\n");
        for (int i = 0; i < TenantDealing.TENANTS; i++) {
            total += counts[i];
            double expected = dealing.expectedShare(i) * DOCS;
            double deviation = Math.abs(counts[i] - expected) / expected;
            maxDeviation = Math.max(maxDeviation, deviation);
            if (i < 5 || i >= 48) {
                histogram.append(
                        String.format(
                                "%s %8.0f %6d dev=%.3f%n",
                                TenantDealing.tenantId(i), expected, counts[i], deviation));
            }
            assertThat(deviation)
                    .as("tenant %s Zipf share (expected %.0f, got %d)",
                            TenantDealing.tenantId(i), expected, counts[i])
                    .isLessThan(REL_TOLERANCE);
        }
        assertThat(total).isEqualTo(DOCS);
        System.out.println(histogram + "max deviation=" + String.format("%.3f", maxDeviation));
    }

    @Test
    void fracCoversUnitInterval() {
        // Hash quality smoke: 20k fracs must span [0,1) without clumping that
        // would starve tail tenants (caught precisely by the histogram above;
        // this pins the endpoints).
        double min = 1.0;
        double max = 0.0;
        for (long d = 0; d < DOCS; d++) {
            double f = TenantDealing.frac(d);
            assertThat(f).isBetween(0.0, 1.0);
            min = Math.min(min, f);
            max = Math.max(max, f);
        }
        assertThat(min).isLessThan(0.001);
        assertThat(max).isGreaterThan(0.999);
    }

    @Test
    void knuthConstantMatchesSpec() {
        assertThat(TenantDealing.KNUTH_A).isEqualTo(2654435761L);
        assertThat(TenantDealing.ZIPF_S).isCloseTo(1.0, within(0.0));
        assertThat(TenantDealing.TENANTS).isEqualTo(50);
    }
}
