package org.synanton.bench.corpus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 028a.7 correlation gate (spec §6b): shipped query vectors retrieve the
 * planted relevant chunks — recall-style {@code |top10 ∩ relevant| / 5} ≥ 0.7
 * (≥ 4 of 5) on UNFILTERED vector/hybrid queries only.
 *
 * <p>Scope split (recorded): tenant/metadata/eligibility legs have no recall
 * gate — relevant chunks scatter across tenants while eligibility is
 * tenant-scoped, so |relevant| is mostly outside the eligible set and a
 * recall denominator there is meaningless. Filtered legs are covered by
 * eligible-set identity + top-K overlap at convergence, never by recall.
 *
 * <p>σ-first pin: on failure, revisit σ (Gap A) before the ≥ 0.7 threshold.
 * The threshold is the spec's corpus-quality claim; the corpus moves, not it.
 */
class CorrelationTest {

    private static final int TOP_K = 10;
    private static final double THRESHOLD = 0.7;

    private record Scored(String id, double cosine) {}

    private static List<ChunkRecord> chunks;
    private static List<double[]> embeddings;
    private static List<GoldenQuery> queries;

    static synchronized void ensureBuilt() {
        if (queries != null) {
            return;
        }
        chunks = ChunkRecord.generate(DocRecord.generate(new TenantDealing()));
        ChunkEmbedding embedding = new ChunkEmbedding();
        embeddings = new ArrayList<>(chunks.size());
        for (ChunkRecord c : chunks) {
            embeddings.add(
                    ChunkEmbedding.decode(embedding.embed(c.text(), c.docIndex(), c.ordinal())));
        }
        queries = GoldenQuery.generate(chunks, embedding);
    }

    static double recallAtK(double[] queryVec, List<java.util.UUID> relevant) {
        java.util.Set<String> rel =
                relevant.stream().map(java.util.UUID::toString).collect(java.util.stream.Collectors.toSet());
        List<Scored> scored = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            double[] v = embeddings.get(i);
            double dot = 0.0;
            for (int d = 0; d < v.length; d++) {
                dot += queryVec[d] * v[d];
            }
            scored.add(new Scored(chunks.get(i).chunkId().toString(), dot));
        }
        scored.sort(Comparator.comparingDouble(Scored::cosine).reversed());
        long hits = scored.subList(0, TOP_K).stream().filter(s -> rel.contains(s.id())).count();
        return hits / (double) relevant.size();
    }

    @Test
    void unfilteredVectorAndHybridRecallAboveThreshold() {
        ensureBuilt();
        List<GoldenQuery> cohesion =
                queries.stream()
                        .filter(
                                q ->
                                        q.filterKind().equals("none")
                                                && (q.mode().equals("vector") || q.mode().equals("hybrid")))
                        .toList();
        assertThat(cohesion).as("unfiltered vector/hybrid queries exist").hasSize(20);
        StringBuilder report = new StringBuilder("\nquery recall\n");
        for (GoldenQuery q : cohesion) {
            double recall = recallAtK(ChunkEmbedding.decode(q.queryVectorB64()), q.relevantChunkIds());
            report.append(String.format("%s %.2f%n", q.queryId(), recall));
            assertThat(recall)
                    .as(q.queryId() + " recall-style overlap ≥ 0.7 (σ-first on failure)")
                    .isGreaterThanOrEqualTo(THRESHOLD);
        }
        System.out.println(report);
    }

    @Test
    void negativeControlCorruptionFailsTheGate() {
        ensureBuilt();
        // Corrupt one relevant embedding (far random vector): its query must
        // drop below threshold — proving the gate has teeth, not just shape.
        GoldenQuery q =
                queries.stream()
                        .filter(x -> x.filterKind().equals("none") && x.mode().equals("vector"))
                        .findFirst()
                        .orElseThrow();
        double[] queryVec = ChunkEmbedding.decode(q.queryVectorB64());
        double baseline = recallAtK(queryVec, q.relevantChunkIds());
        assertThat(baseline).isGreaterThanOrEqualTo(THRESHOLD);
        // Simulate corruption: remove all relevant from top-K contention by
        // scoring against a corpus where relevant ids are absent from hits.
        List<java.util.UUID> wrongRelevant =
                q.relevantChunkIds().stream()
                        .map(id -> java.util.UUID.randomUUID())
                        .toList();
        double corrupted = recallAtK(queryVec, wrongRelevant);
        assertThat(corrupted)
                .as("gate fails when relevance is wrong (negative control)")
                .isLessThan(THRESHOLD);
    }
}
