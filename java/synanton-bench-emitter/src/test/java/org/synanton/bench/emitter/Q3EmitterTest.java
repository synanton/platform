package org.synanton.bench.emitter;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synanton.bench.convergence.RunOutput;
import org.synanton.bench.emitter.QueryExecutor.GoldenInput;
import org.synanton.synquest.inmemory.InMemorySynquestEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A.4 acceptance: emitsParserValidQ3WithManifestVersion — emitter output
 * round-trips through the strict parser, timing_scope pins topology, and the
 * corpus version comes from the manifest (passed in, never guessed).
 */
class Q3EmitterTest {

    @Test
    void emitsParserValidQ3WithManifestVersion() throws Exception {
        QueryExecutor.QueryOutput out =
                new QueryExecutor.QueryOutput(
                        "q1", "lexical", "none", "-",
                        List.of(new QueryExecutor.Hit("c1", 3.0, 0)),
                        List.of("c1"), 0.5, "single");
        String json = Q3Emitter.emit("test-run", "ydb-poc-corpus-v1", List.of(out));
        RunOutput parsed =
                RunOutput.parse(
                        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "q3-test");
        assertThat(parsed.runId()).isEqualTo("test-run");
        assertThat(parsed.corpus()).isEqualTo("ydb-poc-corpus-v1");
        assertThat(parsed.queries()).hasSize(1);
        assertThat(parsed.queries().get(0).timingScope()).isEqualTo("single");
        assertThat(parsed.queries().get(0).topK()).hasSize(1);
    }

    @Test
    void fanoutTimingScopeSurvivesRoundTrip() throws Exception {
        InMemorySynquestEngine engine = new InMemorySynquestEngine();
        QueryExecutor.QueryOutput out =
                QueryExecutor.execute(
                        engine,
                        new GoldenInput("q", "lexical", "x", "",
                                List.of("t1", "t2", "t3"), Map.of(), "-", "tenant", List.of()),
                        List.of("t1", "t2", "t3"));
        assertThat(out.timingScope()).isEqualTo("summed_fanout_3");
        String json = Q3Emitter.emit("r", "c", List.of(out));
        RunOutput parsed =
                RunOutput.parse(
                        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "q3-test");
        assertThat(parsed.queries().get(0).timingScope()).isEqualTo("summed_fanout_3");
    }

    @Test
    void structuralEmptyFlagRoundTrips() throws Exception {
        QueryExecutor.QueryOutput out =
                new QueryExecutor.QueryOutput(
                        "q1", "lexical", "tenant", "0.1%",
                        List.of(), List.of("c1"), 0.5, "single");
        String json =
                Q3Emitter.emit("r", "c", List.of(out), java.util.Set.of("q1"));
        RunOutput parsed =
                RunOutput.parse(
                        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "q3-test");
        assertThat(parsed.queries().get(0).structuralEmpty()).isTrue();
        String json2 = Q3Emitter.emit("r", "c", List.of(out));
        RunOutput parsed2 =
                RunOutput.parse(
                        new ByteArrayInputStream(json2.getBytes(StandardCharsets.UTF_8)), "q3-test");
        assertThat(parsed2.queries().get(0).structuralEmpty())
                .as("absent flag defaults false (old fixtures unaffected)")
                .isFalse();
    }
}
