package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.Test;
import org.synanton.extraction.v1.BindingKind;
import org.synanton.extraction.v1.CoordinateSpace;
import org.synanton.extraction.v1.DocumentElement;
import org.synanton.extraction.v1.DocumentElementType;
import org.synanton.extraction.v1.DocumentPayload;
import org.synanton.extraction.v1.LineEndingPolicy;
import org.synanton.extraction.v1.LocationPrecision;
import org.synanton.extraction.v1.OffsetUnit;
import org.synanton.extraction.v1.Reproducibility;
import org.synanton.extraction.v1.SourceBinding;
import org.synanton.extraction.v1.SourceLocation;
import org.synanton.extraction.v1.TextNormalization;
import org.synanton.extraction.v1.TextRange;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ChunkerConfig;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumerEvidenceCompatibilityTest {

    @Test
    void partialMarkdownEvidenceDoesNotChangeChunkingOrInventOptionalData() {
        DocumentPayload withoutEvidence = payload(false);
        DocumentPayload withEvidence = payload(true);

        var baseline = new SemanticChunkStage(ChunkerConfig.defaults())
                .apply(parsed(withoutEvidence), ctx());
        var withSpans = new SemanticChunkStage(ChunkerConfig.defaults())
                .apply(parsed(withEvidence), ctx());

        assertThat(withEvidence.getSource().getSourceFormat()).isEqualTo("markdown");
        assertThat(withEvidence.getElements(1).getSourceLocationsCount()).isEqualTo(2);
        assertThat(withEvidence.getElements(1).getCanonicalTextRange())
                .isEqualTo(org.synanton.extraction.v1.CanonicalTextRange.getDefaultInstance());
        assertThat(withEvidence.getProvenance())
                .isEqualTo(org.synanton.extraction.v1.ExtractionProvenance.getDefaultInstance());
        assertThat(withSpans.chunks()).hasSameSizeAs(baseline.chunks());
        assertThat(withSpans.chunks()).usingRecursiveComparison()
                .isEqualTo(baseline.chunks());
        assertThat(withSpans.chunks()).allSatisfy(chunk ->
                assertThat(chunk.content()).doesNotContain("source range"));
    }

    private static DocumentPayload payload(boolean withEvidence) {
        var builder = DocumentPayload.newBuilder()
                .setMediaType("text/markdown")
                .addElements(DocumentElement.newBuilder()
                        .setId("e1")
                        .setType(DocumentElementType.ELEMENT_HEADING)
                        .setText("Title")
                        .setLevel(1))
                .addElements(DocumentElement.newBuilder()
                        .setId("e2")
                        .setType(DocumentElementType.ELEMENT_PARAGRAPH)
                        .setText("A paragraph with evidence."));
        if (withEvidence) {
            builder.setSource(org.synanton.extraction.v1.SourceDescriptor.newBuilder()
                    .setMediaType("text/markdown")
                    .setSourceFormat("markdown")
                    .setSourceDigest("a".repeat(64)));
            builder.getElementsBuilder(1)
                    .addSourceLocations(location("span-e2-a", 10, 20))
                    .addSourceLocations(location("span-e2-b", 20, 40));
        }
        return builder.build();
    }

    private static SourceLocation location(String id, long start, long end) {
        return SourceLocation.newBuilder()
                .setLocationId(id)
                .setSourceBinding(SourceBinding.newBuilder()
                        .setKind(BindingKind.BINDING_KIND_INHERIT_DOCUMENT))
                .setPrecision(LocationPrecision.LOCATION_PRECISION_EXACT)
                .setReproducibility(Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE)
                .setTextRange(TextRange.newBuilder()
                        .setUnit(OffsetUnit.OFFSET_UNIT_UTF8_BYTE)
                        .setCoordinateSpace(CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE)
                        .setStart(start)
                        .setEnd(end)
                        .setNormalization(TextNormalization.TEXT_NORMALIZATION_NONE)
                        .setLineEndings(LineEndingPolicy.LINE_ENDING_ORIGINAL))
                .build();
    }

    private static ParsedDocument parsed(DocumentPayload payload) {
        var ref = new ContentRef("file", "file:///test.md", "text/markdown", 100, Instant.now());
        var acquired = new AcquiredDocument(ref, new byte[0], "sha256", "text/markdown",
                "file:///test.md", UUID.fromString("11111111-1111-1111-1111-111111111111"));
        return new ParsedDocument(acquired, "Title\nA paragraph with evidence.", Map.of(), payload);
    }

    private static StageContext ctx() {
        return new StageContext("demo", UUID.randomUUID().toString(), null);
    }
}
