package org.synanton.bench.corpus;

/**
 * 028a.2 deterministic tenant dealing (spec §3): Zipf layout with Knuth-hash
 * dealing and no RNG state.
 *
 * <p>Reference construction (portable — reproducible in any language with
 * IEEE-754 doubles):
 *
 * <pre>
 *   S = 1.0                                     # Zipf exponent, pinned
 *   w(i) = 1 / (i+1)^S   for i in 0..49
 *   total = sum(w)
 *   frac(d) = ((d * 2654435761) mod 2^32) / 2^32   # Knuth multiplicative hash
 *   tenant(d) = smallest i with cumsum(w, 0..i) / total >= frac(d)
 * </pre>
 *
 * <p>Constants: multiplier 2654435761 (2^32 × golden ratio), modulus 2^32 via
 * bitmask (exact in any 64-bit integer type; products for d < 2^32 fit).
 * Boundary ties resolve to the lower index; exact-boundary hits require
 * frac(d) to equal a binary-exact cumulative quotient, which no d in range
 * produces (verified by the distribution test counting every tenant).
 */
public final class TenantDealing {

    /** Zipf exponent, pinned by spec §3. */
    public static final double ZIPF_S = 1.0;

    /** Tenant count, pinned by spec (tenant_00..tenant_49). */
    public static final int TENANTS = 50;

    /** Knuth multiplicative constant (2^32 × φ⁻¹, odd — bijective mod 2^32). */
    public static final long KNUTH_A = 2654435761L;

    /** Modulus mask: mod 2^32. */
    public static final long MOD_MASK = 0xFFFFFFFFL;

    private final double[] boundaries;

    public TenantDealing() {
        double total = 0.0;
        double[] weights = new double[TENANTS];
        for (int i = 0; i < TENANTS; i++) {
            weights[i] = 1.0 / Math.pow(i + 1, ZIPF_S);
            total += weights[i];
        }
        boundaries = new double[TENANTS];
        double cumsum = 0.0;
        for (int i = 0; i < TENANTS; i++) {
            cumsum += weights[i];
            boundaries[i] = cumsum / total;
        }
        boundaries[TENANTS - 1] = 1.0; // exact top: every frac lands somewhere
    }

    /** Unit fraction for document index d (deterministic, stateless). */
    public static double frac(long d) {
        return ((d * KNUTH_A) & MOD_MASK) / 4294967296.0;
    }

    /** Tenant index (0-based) for document index d. */
    public int tenant(long d) {
        double f = frac(d);
        for (int i = 0; i < TENANTS; i++) {
            if (f < boundaries[i] || i == TENANTS - 1) {
                return i;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** Stable tenant id string for document index d. */
    public static String tenantId(int index) {
        return String.format("tenant_%02d", index);
    }

    /** Expected (exact, non-empirical) doc share of tenant i. */
    double expectedShare(int i) {
        double lo = i == 0 ? 0.0 : boundaries[i - 1];
        return boundaries[i] - lo;
    }
}
