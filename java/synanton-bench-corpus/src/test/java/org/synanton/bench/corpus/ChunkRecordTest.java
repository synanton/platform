package org.synanton.bench.corpus;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 028a.4 acceptance: exact counts, distinct planted indices, cluster structure. */
class ChunkRecordTest {

    private static List<ChunkRecord> corpus() {
        return ChunkRecord.generate(DocRecord.generate(new TenantDealing()));
    }

    @Test
    void exactly160kChunks() {
        List<ChunkRecord> chunks = corpus();
        assertThat(chunks).hasSize(160_000);
        Set<java.util.UUID> ids = new HashSet<>();
        chunks.forEach(c -> ids.add(c.chunkId()));
        assertThat(ids).as("all chunk ids unique").hasSize(160_000);
    }

    @Test
    void plantedIndicesDistinctAcrossAllQueries() {
        Set<Integer> all = new HashSet<>();
        for (int q = 0; q < ChunkRecord.QUERIES; q++) {
            List<Integer> idx = ChunkRecord.plantedIndices(q);
            assertThat(idx).as("query " + q + " plants exactly 5").hasSize(5);
            all.addAll(idx);
        }
        assertThat(all).as("600 distinct planted chunks (coprime stride, no collision)").hasSize(600);
    }

    @Test
    void eachQueryPlantsTermsInExactlyFiveChunks() {
        List<ChunkRecord> chunks = corpus();
        for (int q : new int[] {0, 7, 119}) {
            List<String> terms = ChunkRecord.plantedTerms(q);
            long hits =
                    chunks.stream()
                            .filter(
                                    c ->
                                            terms.stream()
                                                    .allMatch(t -> (" " + c.text() + " ").contains(" " + t + " ")))
                            .count();
            assertThat(hits).as("query " + q + " terms co-occur in exactly 5 chunks").isEqualTo(5);
        }
    }

    @Test
    void textLengthsAndIdsStable() {
        List<ChunkRecord> first = corpus();
        List<ChunkRecord> second = corpus();
        assertThat(second).isEqualTo(first);
        assertThat(first.get(0).text().split(" ")).hasSizeBetween(80, 201);
        assertThat(ChunkRecord.chunkId(3, 5).version()).isEqualTo(3);
        System.out.println("sample=" + first.get(0).text().substring(0, 80) + "…");
    }

    @Test
    void metadataShapesMatchGapB() {
        List<ChunkRecord> chunks = corpus();
        Set<String> types = new HashSet<>();
        Set<String> langs = new HashSet<>();
        Set<String> sens = new HashSet<>();
        Set<String> sources = new HashSet<>();
        chunks.forEach(
                c -> {
                    types.add(c.docType());
                    langs.add(c.lang());
                    sens.add(c.sensitivity());
                    sources.add(c.sourceId());
                });
        assertThat(types).hasSize(8);
        assertThat(langs).containsExactlyInAnyOrder("en", "de", "fr");
        assertThat(sens).containsExactlyInAnyOrder("public", "internal", "restricted");
        assertThat(sources).hasSize(200);
    }
}
