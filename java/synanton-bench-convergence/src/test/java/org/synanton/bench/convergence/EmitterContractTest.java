package org.synanton.bench.convergence;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Item 8 (runbook plan): emitter fixture contract test. Every emitter
 * (028b/c/d/e, PG-POC-018) must produce Q3 that satisfies this contract —
 * validated here against fixtures so a non-conforming emitter fails before
 * its output ever reaches the comparator.
 *
 * <p>Contract: {@code corpus} names the manifest version (emitter fails if
 * the manifest is missing — it must never emit a guessed version);
 * every eligibility/metadata-filtered leg carries {@code eligible_set};
 * every leg carries {@code top_k} (possibly empty — emptiness is a verdict
 * input, not a contract break).
 */
class EmitterContractTest {

    private static RunOutput fixture(String name) throws Exception {
        try (InputStream in =
                EmitterContractTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture present: " + name).isNotNull();
            return RunOutput.parse(in, name);
        }
    }

    /** Emitter outputs name the corpus manifest version they ran against. */
    @Test
    void emitterNamesManifestCorpusVersion() throws Exception {
        RunOutput run = fixture("candidate-pass.json");
        assertThat(run.corpus())
                .as("corpus field carries the manifest version, never a guess")
                .isEqualTo("ydb-poc-corpus-v1");
    }

    /** Filtered legs carry eligible sets; unfiltered legs may omit them. */
    @Test
    void filteredLegsCarryEligibleSets() throws Exception {
        RunOutput run = fixture("candidate-pass.json");
        assertThat(run.queries())
                .filteredOn(q -> q.filter().equals("eligibility") || q.filter().equals("metadata"))
                .as("every filtered leg carries eligible_set")
                .allSatisfy(q -> assertThat(q.eligibleSet()).isNotEmpty());
    }

    /** Missing corpus field fails at parse with the filename. */
    @Test
    void missingCorpusFailsLoudly() {
        String json = "{\"run_id\": \"x\", \"queries\": []}";
        assertThatThrownBy(
                        () ->
                                RunOutput.parse(
                                        new java.io.ByteArrayInputStream(
                                                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                        "no-corpus.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corpus")
                .hasMessageContaining("no-corpus.json");
    }

    /** A leg missing top_k parses as empty (verdict input, not contract break). */
    @Test
    void missingTopKParsesAsEmpty() throws Exception {
        String json =
                "{\"run_id\": \"x\", \"corpus\": \"ydb-poc-corpus-v1\", \"queries\": ["
                        + "{\"query_id\": \"q\", \"mode\": \"lexical\"}]}";
        RunOutput run =
                RunOutput.parse(
                        new java.io.ByteArrayInputStream(
                                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "no-topk.json");
        assertThat(run.queries()).hasSize(1);
        assertThat(run.queries().get(0).topK()).isEmpty();
        assertThat(run.queries().get(0).filter()).isEqualTo("none");
    }
}
