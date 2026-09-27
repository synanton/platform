package org.synanton.bench.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 028a.9 acceptance: manifest schema complete, versions single-sourced, and
 * the joint-bump rule enforced by tripwire (watch item 1) — plus the consumer
 * fixture (watch item 2): a stub reader asserting every field the emitter
 * tasks require, so a missing field fails here, not at first emitter build.
 */
class ManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode manifest() {
        CorpusIo.Corpus corpus = CorpusIo.buildSkeletons();
        // File shas stand in for emitAll outputs (determinism pins their values).
        return CorpusManifest.emit(
                corpus, List.of("sha-doc", "sha-chunk", "sha-query"),
                List.of("documents.jsonl", "chunks.jsonl", "golden-queries.jsonl"));
    }

    @Test
    void schemaCompletePerGapC() {
        JsonNode m = manifest();
        assertThat(m.get("corpus_version").asText()).isEqualTo("ydb-poc-corpus-v1");
        assertThat(m.get("seed").asInt()).isEqualTo(42);
        assertThat(m.get("counts").get("documents").asInt()).isEqualTo(20_000);
        assertThat(m.get("counts").get("chunks").asInt()).isEqualTo(160_000);
        assertThat(m.get("counts").get("queries").asInt()).isEqualTo(120);
        assertThat(m.get("leg_eligible_counts").size()).isEqualTo(120);
        assertThat(m.get("files").size()).isEqualTo(3);
        m.get("files")
                .forEach(
                        f -> {
                            assertThat(f.hasNonNull("path")).isTrue();
                            assertThat(f.hasNonNull("sha256")).isTrue();
                        });
        assertThat(m.get("generator").get("name").asText()).isEqualTo("synanton-bench-corpus");
        assertThat(m.get("generator").get("version").asText())
                .as("generator.version single-sourced from ModuleInfo")
                .isEqualTo(ModuleInfo.GENERATOR_VERSION);
    }

    @Test
    void consumerFixtureReadsEveryEmitterRequiredField() {
        // Stub of the 028b/c/d/e + 018 manifest reader: fails here if the
        // schema ever drops a field an emitter needs (watch item 2).
        JsonNode m = manifest();
        Map<String, String> required =
                Map.of(
                        "corpus_version", "Q3 corpus field + comparator gate",
                        "seed", "determinism repro",
                        "counts/documents", "loader expectations",
                        "counts/chunks", "loader expectations",
                        "counts/queries", "query driver sizing",
                        "leg_eligible_counts", "tier report headers",
                        "files", "per-file paths + shas",
                        "generator/version", "reproducibility + joint-bump");
        for (String field : required.keySet()) {
            JsonNode node = m;
            for (String part : field.split("/")) {
                node = node == null ? null : node.get(part);
            }
            assertThat(node)
                    .as("consumer-required field '" + field + "' (" + required.get(field) + ")")
                    .isNotNull();
        }
        // Every golden query has a leg count entry (emitters size tier reports from it).
        Iterator<String> names = m.get("leg_eligible_counts").fieldNames();
        int count = 0;
        while (names.hasNext()) {
            names.next();
            count++;
        }
        assertThat(count).isEqualTo(120);
    }

    @Test
    void jointBumpRuleStatedInManifest() {
        assertThat(manifest().get("joint_bump_rule").asText()).contains("together");
    }
}
