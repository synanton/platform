package org.synanton.bench.corpus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;

/**
 * 028a.5 chunk embedding (spec §2 + Gap A): direction = L2-normalized mean of
 * token directions, plus per-chunk Gaussian perturbation σ = 0.3, re-normalized;
 * stored as base64 of float32 little-endian (spec §3).
 *
 * <p>§6b arithmetic pin (recorded — spec wording "top-K overlap ≥ 0.7" is
 * ambiguous, resolved here): overlap is recall-style,
 * {@code |retrieved ∩ relevant| / |relevant|}. Under the comparator's
 * {@code |A∩B|/max} formula the gate would be unreachable (5 relevant, K=10 →
 * ceiling 0.5). ≥ 0.7 recall-style means ≥ 4 of 5 relevant in top-10.
 * Flagged for a one-line §6b clarification at 028a.11 closeout.
 */
public final class ChunkEmbedding {

    public static final int DIMS = 384;

    /** Per-chunk perturbation σ (Gap A pin — revisit σ first if §6b fails). */
    public static final double CHUNK_SIGMA = 0.3;

    private static final long NOISE_XOR = 0x5EED5EED5EED5EEDL;

    private final TokenDirections directions = new TokenDirections();

    /** Base64 float32-LE embedding for one chunk's text (deterministic). */
    public String embed(String text, int docIndex, int ordinal) {
        String[] tokens = text.split(" ");
        double[] mean = new double[DIMS];
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            double[] dir = directions.direction(token);
            for (int d = 0; d < DIMS; d++) {
                mean[d] += dir[d];
            }
        }
        SplitMix64 noise = new SplitMix64(SplitMix64.seedFor(docIndex, ordinal) ^ NOISE_XOR);
        for (int d = 0; d < DIMS; d++) {
            mean[d] += TokenDirections.rngGaussian(noise) * CHUNK_SIGMA;
        }
        return encode(TokenDirections.normalize(mean));
    }

    /** L2-normalized centroid of chunk embeddings (for .6 query vectors). */
    public static double[] centroid(List<double[]> vectors) {
        double[] mean = new double[DIMS];
        vectors.forEach(v -> {
            for (int d = 0; d < DIMS; d++) {
                mean[d] += v[d];
            }
        });
        return TokenDirections.normalize(mean);
    }

    /** Base64 of float32 little-endian (the only representation — no decimals). */
    public static String encode(double[] unit) {
        ByteBuffer buf = ByteBuffer.allocate(DIMS * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (double x : unit) {
            buf.putFloat((float) x);
        }
        return Base64.getEncoder().encodeToString(buf.array());
    }

    public static double[] decode(String b64) {
        byte[] bytes = Base64.getDecoder().decode(b64);
        if (bytes.length != DIMS * 4) {
            throw new IllegalArgumentException("embedding bytes != 384×float32: " + bytes.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double[] out = new double[DIMS];
        for (int d = 0; d < DIMS; d++) {
            out[d] = buf.getFloat();
        }
        return out;
    }
}
