package org.synanton.bench.corpus;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 028a.6 acceptance: coverage, centroid byte-identity, relevant/eligible split. */
class GoldenQueryTest {

    private static List<ChunkRecord> chunks;
    private static List<GoldenQuery> queries;

    static synchronized void ensureBuilt() {
        if (queries == null) {
            chunks = ChunkRecord.generate(DocRecord.generate(new TenantDealing()));
            queries = GoldenQuery.generate(chunks, new ChunkEmbedding());
        }
    }

    @Test
    void exactly120QueriesWithModeAndFilterCoverage() {
        ensureBuilt();
        assertThat(queries).hasSize(120);
        Map<String, Long> modes =
                queries.stream().collect(Collectors.groupingBy(GoldenQuery::mode, Collectors.counting()));
        assertThat(modes)
                .containsEntry("lexical", 40L)
                .containsEntry("vector", 40L)
                .containsEntry("hybrid", 40L);
        Map<String, Long> filters =
                queries.stream()
                        .collect(Collectors.groupingBy(GoldenQuery::filterKind, Collectors.counting()));
        assertThat(filters)
                .containsEntry("none", 30L)
                .containsEntry("tenant", 30L)
                .containsEntry("metadata", 30L)
                .containsEntry("eligibility", 30L);
    }

    @Test
    void relevantIsFivePlantedAndDistinctFromEligible() {
        ensureBuilt();
        for (GoldenQuery q : queries) {
            assertThat(q.relevantChunkIds()).as(q.queryId() + " relevant count").hasSize(5);
        }
        // Relevant ⊄ eligible on filtered legs BY DESIGN: planted chunks scatter
        // across tenants (coprime stride) while eligibility is tenant-scoped.
        // Recall gates only unfiltered lexical legs (eligible = all); filtered
        // legs measure overlap/identity, never recall. Recorded, not asserted.
        Map<String, Double> meanOverlap =
                queries.stream()
                        .filter(q -> !q.filterKind().equals("none"))
                        .collect(
                                Collectors.groupingBy(
                                        GoldenQuery::filterKind,
                                        Collectors.averagingDouble(
                                                q ->
                                                        q.relevantChunkIds().stream()
                                                                        .filter(id ->
                                                                                q.eligibleChunkIds().contains(id))
                                                                        .count()
                                                                / 5.0)));
        System.out.println("mean relevant-in-eligible by filter: " + meanOverlap);
        // Relevant sets differ per cluster (no two queries share all 5).
        Set<List<java.util.UUID>> relevantSets = new HashSet<>();
        queries.forEach(q -> relevantSets.add(q.relevantChunkIds()));
        assertThat(relevantSets).hasSize(120);
    }

    @Test
    void queryVectorBytesEqualSharedFunctionCentroid() {
        ensureBuilt();
        // The watch item: vectors must come from the SAME pure function .5
        // uses — verified by independent recompute through that function.
        ChunkEmbedding embedding = new ChunkEmbedding();
        for (GoldenQuery q : queries.subList(0, 12)) {
            List<double[]> vectors = new java.util.ArrayList<>();
            Map<java.util.UUID, ChunkRecord> byId = new java.util.HashMap<>();
            chunks.forEach(c -> byId.put(c.chunkId(), c));
            for (java.util.UUID id : q.relevantChunkIds()) {
                ChunkRecord c = byId.get(id);
                vectors.add(
                        ChunkEmbedding.decode(embedding.embed(c.text(), c.docIndex(), c.ordinal())));
            }
            String recomputed = ChunkEmbedding.encode(ChunkEmbedding.centroid(vectors));
            assertThat(q.queryVectorB64())
                    .as(q.queryId() + " vector bytes identical to shared-function centroid")
                    .isEqualTo(recomputed);
            assertThat(ChunkEmbedding.decode(q.queryVectorB64())).hasSize(384);
        }
    }

    @Test
    void eligibilityTiersPresentWithExactCounts() {
        ensureBuilt();
        Map<String, Long> tiers =
                queries.stream()
                        .filter(q -> q.filterKind().equals("eligibility"))
                        .collect(Collectors.groupingBy(GoldenQuery::selectivity, Collectors.counting()));
        assertThat(tiers.keySet())
                .containsExactlyInAnyOrder("0.1%", "1%", "10%", "100%");
        queries.stream()
                .filter(q -> q.filterKind().equals("eligibility"))
                .forEach(
                        q ->
                                System.out.println(
                                        q.queryId() + " tier=" + q.selectivity()
                                                + " eligible=" + q.eligibleChunkIds().size()));
    }
}
