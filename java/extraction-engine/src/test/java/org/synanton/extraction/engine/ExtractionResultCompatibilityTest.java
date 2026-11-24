package org.synanton.extraction.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractionResultCompatibilityTest {

    @Test
    void canonicalConstructorRetainsAllConsumerFields() {
        PreEntity entity = new PreEntity("m1", "Acme", "Supplier", 0, 4, "Acme");
        RelationCandidate relation = new RelationCandidate("m1", "supplies", "m2", "c1", 0, 4);
        LlmUsage usage = new LlmUsage("stop", 10, 5, 15);
        QuarantinedLine quarantine = new QuarantinedLine(1, "bad", "SCHEMA", "detail",
                "STRUCTURAL");
        LlmProvenance provenance = new LlmProvenance("extraction-v2", "a".repeat(64),
                "b".repeat(64), "schema-v1", "c".repeat(64));

        ExtractionResult result = new ExtractionResult(List.of(entity), List.of(relation),
                usage, List.of(quarantine), ExtractionIntegrity.TRUNCATED, provenance);

        assertThat(result.preEntities()).containsExactly(entity);
        assertThat(result.relations()).containsExactly(relation);
        assertThat(result.llmUsage()).isEqualTo(usage);
        assertThat(result.quarantined()).containsExactly(quarantine);
        assertThat(result.integrity()).isEqualTo(ExtractionIntegrity.TRUNCATED);
        assertThat(result.provenance()).isEqualTo(provenance);
    }

    @Test
    void legacyConstructorsKeepTheFrozenDefaults() {
        PreEntity entity = new PreEntity("m1", "Acme", "Supplier", 0, 4, "Acme");

        ExtractionResult twoArg = new ExtractionResult(List.of(entity), List.of());
        assertThat(twoArg.llmUsage()).isEqualTo(LlmUsage.NONE);
        assertThat(twoArg.quarantined()).isEmpty();
        assertThat(twoArg.integrity()).isEqualTo(ExtractionIntegrity.CLEAN);
        assertThat(twoArg.provenance()).isEqualTo(LlmProvenance.NONE);

        ExtractionResult threeArg = new ExtractionResult(List.of(entity), List.of(),
                new LlmUsage("length", 1, 2, 3));
        assertThat(threeArg.quarantined()).isEmpty();
        assertThat(threeArg.integrity()).isEqualTo(ExtractionIntegrity.CLEAN);
        assertThat(threeArg.provenance()).isEqualTo(LlmProvenance.NONE);
    }
}
