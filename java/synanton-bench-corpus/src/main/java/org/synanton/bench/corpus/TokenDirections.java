package org.synanton.bench.corpus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 028a.5 token → direction map (spec §2, Gap A). Load-bearing pin: cluster
 * centroids are defined FIRST; planted terms lie near their centroid; only
 * then does chunk direction follow. A hash-to-random-direction map would
 * scatter each cluster's planted terms and make §6b unpassable at any σ —
 * this construction is what makes correlation achievable, not σ alone.
 *
 * <ul>
 *   <li>Cluster centroid c_q: unit Gaussian vector from a dedicated
 *       SplitMix64 stream seeded {@code seedFor(q, 0xC10STER)} — fixed before
 *       any chunk is embedded.
 *   <li>Planted term {@code gold<q>{a,b,c}}: normalize(c_q + Gaussian×0.15
 *       per dim) — three directions inside a small cone of c_q.
 *   <li>Background token: unit Gaussian vector seeded by the token string
 *       (unsigned hashCode mixed with a golden constant) — random, and with
 *       probability ~1 unaligned with every centroid.
 * </ul>
 *
 * <p>Deterministic and order-independent (pure functions + cache).
 */
final class TokenDirections {

    static final double PLANT_CONE_SIGMA = 0.03;
    static final int CLUSTER_SEED_ORDINAL = 0xC10;

    private final Map<String, double[]> cache = new ConcurrentHashMap<>();

    /** Unit cluster centroid for query q (120 clusters). */
    double[] centroid(int query) {
        return cache.computeIfAbsent("centroid:" + query, k -> {
            SplitMix64 rng = new SplitMix64(SplitMix64.seedFor(query, CLUSTER_SEED_ORDINAL));
            return normalize(gaussianVec(rng));
        });
    }

    /** Unit direction for any token (planted terms resolve to their cluster cone). */
    double[] direction(String token) {
        return cache.computeIfAbsent("tok:" + token, k -> resolve(token));
    }

    private double[] resolve(String token) {
        if (token.startsWith("gold")) {
            // gold<q>{a,b,c}: q = digits, sub = trailing letter.
            int i = 4;
            while (i < token.length() && Character.isDigit(token.charAt(i))) {
                i++;
            }
            int q = Integer.parseInt(token.substring(4, i));
            SplitMix64 rng =
                    new SplitMix64(
                            SplitMix64.seedFor(q * 31 + (token.charAt(token.length() - 1) - 'a'), 0x9E3));
            double[] centroid = centroid(q);
            double[] v = new double[ChunkEmbedding.DIMS];
            for (int d = 0; d < v.length; d++) {
                v[d] = centroid[d] + rngGaussian(rng) * PLANT_CONE_SIGMA;
            }
            return normalize(v);
        }
        long seed = (Integer.toUnsignedLong(token.hashCode()) * 0x9E3779B97F4A7C15L) ^ 0x51F7A9B3L;
        return normalize(gaussianVec(new SplitMix64(seed)));
    }

    static double[] gaussianVec(SplitMix64 rng) {
        double[] v = new double[ChunkEmbedding.DIMS];
        for (int d = 0; d < v.length; d++) {
            v[d] = rngGaussian(rng);
        }
        return v;
    }

    /** Box-Muller from SplitMix64 output (deterministic, stateless per call order). */
    static double rngGaussian(SplitMix64 rng) {
        double u1 = (rng.nextLong() >>> 11) * 0x1.0p-53 + 0x1.0p-53;
        double u2 = (rng.nextLong() >>> 11) * 0x1.0p-53;
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    static double[] normalize(double[] v) {
        double norm = 0.0;
        for (double x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i] / norm;
        }
        return out;
    }
}
