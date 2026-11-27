package org.synanton.synflux.document.evidence;

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
import org.synanton.extraction.v1.SourceDescriptor;
import org.synanton.extraction.v1.SourceLocation;
import org.synanton.extraction.v1.TextNormalization;
import org.synanton.extraction.v1.TextRange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class SourceSpanEvidenceBinderTest {

    private static final String SOURCE = "# Heading\r\n\r\nParagraph text.\r\n\r\n- item\r\n\r\n```\r\ncode\r\n```\r\n";
    private static final byte[] BYTES = SOURCE.getBytes(StandardCharsets.UTF_8);
    private static final String DIGEST = sha256(BYTES);
    private final EvidenceBinder binder = new EvidenceBinder();

    @Test
    void resolvesExactMarkdownHeadingRange() {
        EvidenceBinder.SourceSpanResult result = binder.bindSourceSpan(payload(location("loc-1", 0, 9,
                LocationPrecision.LOCATION_PRECISION_EXACT, Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
                CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE)), BYTES,
                candidate("exact", "loc-1", EvidenceBinder.Grounding.EXACT, "# Heading"));

        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.RESOLVED);
        assertThat(result.evidence().sourceText()).isEqualTo("# Heading");
        assertThat(result.evidence().sourceStart()).isZero();
        assertThat(result.evidence().sourceEnd()).isEqualTo(9);
        assertThat(result.evidence().sourceDigest()).isEqualTo(DIGEST);
    }

    @Test
    void resolvesExactParagraphListAndCodeRanges() {
        assertExactRange("Paragraph text.", "e1", "Paragraph text.");
        assertExactRange("- item", "e1", "- item");
        assertExactRange("code", "e1", "code");
    }

    @Test
    void resolvesSupplementaryUnicodeRange() {
        String expected = "# 😀 Distributed Systems";
        String unicode = expected + "\n";
        byte[] bytes = unicode.getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        var payload = DocumentPayload.newBuilder()
            .setSource(source(digest))
            .addElements(element(location("loc-u", 0, expected.getBytes(StandardCharsets.UTF_8).length,
                LocationPrecision.LOCATION_PRECISION_EXACT,
                Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
                CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE)))
            .build();

        var result = binder.bindSourceSpan(payload, bytes,
            candidate("u", "loc-u", EvidenceBinder.Grounding.EXACT, expected));

        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.RESOLVED);
        assertThat(result.evidence().sourceText()).isEqualTo(expected);
    }

    @Test
    void approximateEvidenceIsMisboundUntilAMappingContractExists() {
        var approximate = payload(location("loc-approx", 0, 9,
                LocationPrecision.LOCATION_PRECISION_APPROXIMATE,
                Reproducibility.REPRODUCIBILITY_DEPENDS_ON_NORMALIZATION,
                CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE));

        var result = binder.bindSourceSpan(approximate, BYTES,
                candidate("approx", "loc-approx", EvidenceBinder.Grounding.APPROXIMATE, "# Heading"));

        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.MISBOUND);
        assertThat(result.reason()).isEqualTo("APPROXIMATE_MAPPING_UNSUPPORTED");
    }

    @Test
    void unavailableLocationAndAbsentLocationAreUnmapped() {
        var unavailable = payload(location("loc-unavailable", 0, 9,
                LocationPrecision.LOCATION_PRECISION_UNAVAILABLE,
                Reproducibility.REPRODUCIBILITY_NOT_REPRODUCIBLE,
                CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE));
        var absent = payload(null);

        assertThat(binder.bindSourceSpan(unavailable, BYTES,
                candidate("u", "loc-unavailable", EvidenceBinder.Grounding.EXACT, null)).status())
            .isEqualTo(EvidenceBinder.Status.UNMAPPED);
        assertThat(binder.bindSourceSpan(absent, BYTES,
                new EvidenceBinder.SourceSpanCandidate("a", "e1", null,
                    EvidenceBinder.Grounding.EXACT, null)).status())
            .isEqualTo(EvidenceBinder.Status.UNMAPPED);
    }

    @Test
    void malformedOrContradictoryEvidenceIsMisbound() {
        var wrongDigest = payloadWithDigest(location("loc", 0, 9,
                LocationPrecision.LOCATION_PRECISION_EXACT, Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
                CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE), "a".repeat(64));
        var outOfBounds = payload(location("loc", 0, BYTES.length + 1,
                LocationPrecision.LOCATION_PRECISION_EXACT, Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
                CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE));
        var unsupportedSpace = payload(location("loc", 0, 9,
                LocationPrecision.LOCATION_PRECISION_EXACT, Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
                CoordinateSpace.COORDINATE_SPACE_SOURCE_TEXT_LAYER));
        var wrongText = payload(location("loc", 0, 9,
                LocationPrecision.LOCATION_PRECISION_EXACT, Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
                CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE));

        assertThat(binder.bindSourceSpan(wrongDigest, BYTES,
                candidate("d", "loc", EvidenceBinder.Grounding.EXACT, "# Heading")).reason())
            .isEqualTo("SOURCE_DIGEST_MISMATCH");
        assertThat(binder.bindSourceSpan(outOfBounds, BYTES,
                candidate("o", "loc", EvidenceBinder.Grounding.EXACT, null)).reason())
            .isEqualTo("SOURCE_SPAN_OUT_OF_RANGE");
        assertThat(binder.bindSourceSpan(unsupportedSpace, BYTES,
                candidate("s", "loc", EvidenceBinder.Grounding.EXACT, null)).reason())
            .isEqualTo("UNSUPPORTED_COORDINATE_SPACE");
        assertThat(binder.bindSourceSpan(wrongText, BYTES,
                candidate("t", "loc", EvidenceBinder.Grounding.EXACT, "not the source")).reason())
            .isEqualTo("EXPECTED_SOURCE_TEXT_MISMATCH");
    }

    @Test
    void suppliedLocationIdWithNoAvailableLocationsIsMisbound() {
        var noLocations = payloadWithDigest(null, DIGEST);
        var result = binder.bindSourceSpan(noLocations, BYTES,
            new EvidenceBinder.SourceSpanCandidate("missing", "e1", "loc-missing",
                EvidenceBinder.Grounding.EXACT, null));

        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.MISBOUND);
        assertThat(result.reason()).isEqualTo("UNKNOWN_SOURCE_LOCATION");
    }

    @Test
    void selectedLocationWithBlankIdIsMisbound() {
        var payload = payload(location("", 0, 9,
            LocationPrecision.LOCATION_PRECISION_EXACT,
            Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
            CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE));

        var result = binder.bindSourceSpan(payload, BYTES,
            new EvidenceBinder.SourceSpanCandidate("blank", "e1", null,
                EvidenceBinder.Grounding.EXACT, "# Heading"));

        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.MISBOUND);
        assertThat(result.reason()).isEqualTo("SOURCE_LOCATION_ID_MISSING");
    }

    @Test
    void duplicateMatchingLocationIdsAreMisbound() {
        SourceLocation first = location("loc-1", 0, 9,
            LocationPrecision.LOCATION_PRECISION_EXACT, Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
            CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE);
        SourceLocation second = location("loc-1", 0, 9,
            LocationPrecision.LOCATION_PRECISION_EXACT, Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
            CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE);
        var result = binder.bindSourceSpan(payload(first, second), BYTES,
            candidate("duplicate", "loc-1", EvidenceBinder.Grounding.EXACT, "# Heading"));

        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.MISBOUND);
        assertThat(result.reason()).isEqualTo("DUPLICATE_SOURCE_LOCATION_ID");
    }

    @Test
    void suppliedUnknownLocationIdIsMisbound() {
        var result = binder.bindSourceSpan(payload(location("loc-1", 0, 9,
                LocationPrecision.LOCATION_PRECISION_EXACT,
                Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
                CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE)), BYTES,
            candidate("bad-location", "loc-missing", EvidenceBinder.Grounding.EXACT, "# Heading"));

        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.MISBOUND);
        assertThat(result.reason()).isEqualTo("UNKNOWN_SOURCE_LOCATION");
    }

    @Test
    void unavailableLocationWithoutTextRangeIsUnmapped() {
        SourceLocation unavailable = SourceLocation.newBuilder()
            .setLocationId("loc-unavailable")
            .setSourceBinding(SourceBinding.newBuilder().setKind(BindingKind.BINDING_KIND_INHERIT_DOCUMENT))
            .setPrecision(LocationPrecision.LOCATION_PRECISION_UNAVAILABLE)
            .setReproducibility(Reproducibility.REPRODUCIBILITY_NOT_REPRODUCIBLE)
            .build();

        var result = binder.bindSourceSpan(payload(unavailable), BYTES,
            candidate("u", "loc-unavailable", EvidenceBinder.Grounding.EXACT, null));

        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.UNMAPPED);
        assertThat(result.reason()).isEqualTo("SOURCE_LOCATION_UNAVAILABLE");
    }

    @Test
    void invalidUtf8BoundariesAreMisbound() {
        String unicode = "# 😀 Distributed Systems\n";
        byte[] bytes = unicode.getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        int emojiStart = unicode.indexOf("😀");
        int byteStart = unicode.substring(0, emojiStart).getBytes(StandardCharsets.UTF_8).length;

        var insideStart = withRange(bytes, digest, byteStart + 1, byteStart + 5);
        var insideEnd = withRange(bytes, digest, byteStart, byteStart + 3);
        byte[] malformedBytes = new byte[]{'#', ' ', (byte) 0xC3, 0x28};
        var malformed = withRange(malformedBytes, sha256(malformedBytes), 0, 4);

        assertThat(binder.bindSourceSpan(insideStart, bytes,
                candidate("s", "loc", EvidenceBinder.Grounding.EXACT, null)).reason())
            .isEqualTo("INVALID_UTF8_BOUNDARY");
        assertThat(binder.bindSourceSpan(insideEnd, bytes,
                candidate("e", "loc", EvidenceBinder.Grounding.EXACT, null)).reason())
            .isEqualTo("INVALID_UTF8_BOUNDARY");
        assertThat(binder.bindSourceSpan(malformed, malformedBytes,
                candidate("m", "loc", EvidenceBinder.Grounding.EXACT, null)).reason())
            .isEqualTo("INVALID_UTF8_BOUNDARY");
    }

    @Test
    void sourceVersionMismatchIsMisbound() {
        var result = binder.bindSourceSpan(payload(location("loc", 0, 9,
                LocationPrecision.LOCATION_PRECISION_EXACT,
                Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
                CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE)), BYTES,
            new EvidenceBinder.SourceSpanCandidate("v", "e1", "loc",
                EvidenceBinder.Grounding.EXACT, "# Heading", "v2"));

        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.MISBOUND);
        assertThat(result.reason()).isEqualTo("SOURCE_VERSION_MISMATCH");
    }

    private static DocumentPayload withRange(byte[] source, String digest, int start, int end) {
        return DocumentPayload.newBuilder()
            .setSource(source(digest))
            .addElements(element(location("loc", start, end,
                LocationPrecision.LOCATION_PRECISION_EXACT,
                Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
                CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE)))
            .build();
    }

    @Test
    void ambiguousLocationsAndMissingSourceIdentityAreMisbound() {
        SourceLocation first = location("loc-1", 0, 9,
            LocationPrecision.LOCATION_PRECISION_EXACT, Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
            CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE);
        SourceLocation second = location("loc-2", 0, 9,
            LocationPrecision.LOCATION_PRECISION_EXACT, Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
            CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE);
        var ambiguous = payload(first, second);
        var noIdentity = DocumentPayload.newBuilder()
            .addElements(element(first))
            .build();

        assertThat(binder.bindSourceSpan(ambiguous, BYTES,
                new EvidenceBinder.SourceSpanCandidate("a", "e1", "", EvidenceBinder.Grounding.EXACT,
                        "# Heading")).reason()).isEqualTo("AMBIGUOUS_SOURCE_LOCATION");
        assertThat(binder.bindSourceSpan(noIdentity, BYTES,
                candidate("m", "loc-1", EvidenceBinder.Grounding.EXACT, "# Heading")).reason())
            .isEqualTo("SOURCE_IDENTITY_MISSING");
    }

    private void assertExactRange(String expected, String elementId, String expectedText) {
        int start = SOURCE.indexOf(expected);
        var result = binder.bindSourceSpan(
            payload(location("loc", start, start + expected.getBytes(StandardCharsets.UTF_8).length,
                LocationPrecision.LOCATION_PRECISION_EXACT,
                Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE,
                CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE)),
            BYTES,
            new EvidenceBinder.SourceSpanCandidate("c", elementId, "loc",
                EvidenceBinder.Grounding.EXACT, expectedText));
        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.RESOLVED);
        assertThat(result.evidence().sourceText()).isEqualTo(expectedText);
    }

    private static DocumentPayload payload(SourceLocation location) {
        return payloadWithDigest(location, DIGEST);
    }

    private static DocumentPayload payload(SourceLocation first, SourceLocation second) {
        return DocumentPayload.newBuilder()
            .setSource(source(DIGEST))
            .addElements(element(first).toBuilder().addSourceLocations(second))
            .build();
    }

    private static DocumentPayload payloadWithDigest(SourceLocation location, String digest) {
        DocumentPayload.Builder builder = DocumentPayload.newBuilder().setSource(source(digest));
        if (location != null) {
            builder.addElements(element(location));
        } else {
            builder.addElements(DocumentElement.newBuilder()
                .setId("e1").setType(DocumentElementType.ELEMENT_PARAGRAPH).setText("Heading"));
        }
        return builder.build();
    }

    private static SourceDescriptor source(String digest) {
        return SourceDescriptor.newBuilder()
            .setMediaType("text/markdown")
            .setSourceFormat("markdown")
            .setSourceDigest(digest)
            .setSourceVersion("v1")
            .build();
    }

    private static DocumentElement element(SourceLocation location) {
        return DocumentElement.newBuilder()
            .setId("e1")
            .setType(DocumentElementType.ELEMENT_HEADING)
            .setText("Heading")
            .addSourceLocations(location)
            .build();
    }

    private static EvidenceBinder.SourceSpanCandidate candidate(String id, String locationId,
                                                                 EvidenceBinder.Grounding grounding,
                                                                 String expected) {
        return new EvidenceBinder.SourceSpanCandidate(id, "e1", locationId, grounding, expected);
    }

    private static SourceLocation location(String id, long start, long end,
                                           LocationPrecision precision,
                                           Reproducibility reproducibility,
                                           CoordinateSpace space) {
        return SourceLocation.newBuilder()
            .setLocationId(id)
            .setSourceBinding(SourceBinding.newBuilder().setKind(BindingKind.BINDING_KIND_INHERIT_DOCUMENT))
            .setPrecision(precision)
            .setReproducibility(reproducibility)
            .setTextRange(TextRange.newBuilder()
                .setUnit(OffsetUnit.OFFSET_UNIT_UTF8_BYTE)
                .setCoordinateSpace(space)
                .setStart(start)
                .setEnd(end)
                .setNormalization(TextNormalization.TEXT_NORMALIZATION_NONE)
                .setLineEndings(LineEndingPolicy.LINE_ENDING_ORIGINAL))
            .build();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
