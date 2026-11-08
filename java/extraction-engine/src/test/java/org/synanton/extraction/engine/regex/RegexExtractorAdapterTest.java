package org.synanton.extraction.engine.regex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.extraction.engine.llm.LlmExtractionAdapter;

class RegexExtractorAdapterTest {

    private static final ExtractionContext CONTEXT = new ExtractionContext("tenant", "doc", Set.of());

    @Test
    void extractsAMentionAndACueRelationWithValidOffsets() {
        String text = "Project Halcyon-7 requires a Protocol. The relief valve is part of the secondary manifold.";
        ExtractionResult result = new RegexExtractorAdapter().extract(new TextSegment("c1", text, 0), CONTEXT);

        assertThat(result.preEntities())
                .anyMatch(entity -> entity.surfaceForm().contains("Halcyon-7"));
        assertThat(result.preEntities()).allSatisfy(entity ->
                assertThat(text.substring(entity.charStart(), entity.charEnd()))
                        .isEqualTo(entity.surfaceForm()));
        assertThat(result.relations())
                .anyMatch(relation -> relation.predicate().equals("requires")
                        && relation.subjectMentionId() != null
                        && relation.objectMentionId() != null);
        assertThat(result.relations()).allSatisfy(relation ->
                assertThat(relation.evidenceCharEnd()).isGreaterThan(relation.evidenceCharStart()));
    }

    @Test
    void llmStubReturnsAnEmptyResult() {
        ExtractionResult result = new LlmExtractionAdapter()
                .extract(new TextSegment("c1", "anything", 0), CONTEXT);
        assertThat(result.preEntities()).isEmpty();
        assertThat(result.relations()).isEmpty();
    }
}
