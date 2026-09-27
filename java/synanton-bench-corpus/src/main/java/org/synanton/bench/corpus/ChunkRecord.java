package org.synanton.bench.corpus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 028a.4 chunk generation with cluster-vocabulary skew (spec §1 counts, Gap A
 * token-direction construction).
 *
 * <p>Load-bearing co-design with .5/.6: each of the 120 golden queries owns a
 * 3-term cluster ({@code gold<q>a/b/c}); exactly 5 chunks carry the planted
 * terms; everything else is background salad from a 5,000-token vocab. .5
 * derives embedding direction from these tokens; .6 centroids the 5 planted
 * chunks per query. The chain's shared definition is: <b>chunk text is the
 * single source of truth</b> (.5 tokenizes on whitespace — no side channels).
 *
 * <p>Planted-chunk placement: global chunk index
 * {@code ((q*5+k)*267) mod 160000}, q in 0..119, k in 0..4. 267 is coprime to
 * 160000, so all 600 planted indices are distinct by construction.
 *
 * <p>Chunk ids derived like doc ids: {@code nameUUIDFromBytes("v1-chunk-&lt;doc&gt;-&lt;ord&gt;")}.
 */
public record ChunkRecord(
        int docIndex,
        int ordinal,
        UUID chunkId,
        UUID docId,
        String tenantId,
        String text,
        String docType,
        String lang,
        String sensitivity,
        String sourceId) {

    public static final int QUERIES = 120;
    public static final int PLANTED_PER_QUERY = 5;
    public static final int TOTAL_CHUNKS = DocRecord.DOCS * DocRecord.CHUNKS_PER_DOC;

    static final String[] DOC_TYPES = {
        "memo", "report", "email", "spec", "proposal", "minutes", "policy", "brief"
    };
    static final String[] LANGS = {"en", "de", "fr"};
    static final String[] SENSITIVITIES = {"public", "internal", "restricted"};

    public static UUID chunkId(int docIndex, int ordinal) {
        return UUID.nameUUIDFromBytes(
                ("v1-chunk-" + docIndex + "-" + ordinal).getBytes(StandardCharsets.UTF_8));
    }

    public static List<String> plantedTerms(int query) {
        return List.of("gold" + query + "a", "gold" + query + "b", "gold" + query + "c");
    }

    /** Global chunk indices carrying planted terms for query q (5 distinct). */
    public static List<Integer> plantedIndices(int query) {
        List<Integer> out = new ArrayList<>(PLANTED_PER_QUERY);
        for (int k = 0; k < PLANTED_PER_QUERY; k++) {
            out.add((int) (((long) (query * PLANTED_PER_QUERY + k) * 267) % TOTAL_CHUNKS));
        }
        return List.copyOf(out);
    }

    public static List<ChunkRecord> generate(List<DocRecord> docs) {
        int[] plantQuery = new int[TOTAL_CHUNKS];
        java.util.Arrays.fill(plantQuery, -1);
        for (int q = 0; q < QUERIES; q++) {
            for (int idx : plantedIndices(q)) {
                if (plantQuery[idx] != -1) {
                    throw new IllegalStateException("planted index collision at " + idx);
                }
                plantQuery[idx] = q;
            }
        }
        List<ChunkRecord> chunks = new ArrayList<>(TOTAL_CHUNKS);
        for (DocRecord doc : docs) {
            for (int o = 0; o < DocRecord.CHUNKS_PER_DOC; o++) {
                int global = doc.docIndex() * DocRecord.CHUNKS_PER_DOC + o;
                chunks.add(build(doc, o, plantQuery[global]));
            }
        }
        return List.copyOf(chunks);
    }

    private static ChunkRecord build(DocRecord doc, int ordinal, int query) {
        SplitMix64 rng = new SplitMix64(SplitMix64.seedFor(doc.docIndex(), ordinal));
        StringBuilder text = new StringBuilder();
        int len = 80 + rng.nextInt(121);
        for (int t = 0; t < len; t++) {
            text.append("tok").append(rng.nextInt(5000)).append(' ');
        }
        if (query >= 0) {
            // Planted terms repeat 4× (title/heading-like emphasis). Without
            // repetition, ~137 background tokens drown 3 planted directions
            // (noise norm ≈ 13 vs signal ≈ 3) and no σ makes §6b passable —
            // the failure would masquerade as "σ is wrong". Repetition is the
            // documented signal budget, not a tuning knob.
            for (int r = 0; r < 4; r++) {
                for (String term : plantedTerms(query)) {
                    text.append(term).append(' ');
                }
            }
        }
        int d = doc.docIndex();
        return new ChunkRecord(
                d,
                ordinal,
                chunkId(d, ordinal),
                doc.docId(),
                doc.tenantId(),
                text.toString().stripTrailing(),
                DOC_TYPES[d % DOC_TYPES.length],
                LANGS[(d / DOC_TYPES.length) % LANGS.length],
                SENSITIVITIES[(d / (DOC_TYPES.length * LANGS.length)) % SENSITIVITIES.length],
                String.format("source_%03d", d % 200));
    }
}
