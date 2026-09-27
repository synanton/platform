package org.synanton.bench.corpus;

/**
 * 028a.4 deterministic per-chunk PRNG (SplitMix64, Steele et al. — published
 * algorithm, portable to any language with 64-bit ints). Seeded per
 * (docIndex, ordinal), so chunk text is stateless: no stream order, no shared
 * {@code Random}, regeneration-safe.
 */
final class SplitMix64 {

    private long state;

    SplitMix64(long seed) {
        this.state = seed;
    }

    static long seedFor(int docIndex, int ordinal) {
        return ((long) docIndex << 32) | (ordinal & 0xFFFFFFFFL);
    }

    long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform int in [0, bound). bound must be positive. */
    int nextInt(int bound) {
        return (int) Long.remainderUnsigned(nextLong() >>> 11, bound);
    }
}
