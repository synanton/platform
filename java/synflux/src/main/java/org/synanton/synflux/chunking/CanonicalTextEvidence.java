package org.synanton.synflux.chunking;

import org.synanton.extraction.v1.CanonicalTextIdentity;
import org.synanton.extraction.v1.CanonicalTextRange;
import org.synanton.extraction.v1.DocumentElement;
import org.synanton.extraction.v1.DocumentPayload;
import org.synanton.extraction.v1.DocumentProjection;
import org.synanton.extraction.v1.LineEndingPolicy;
import org.synanton.extraction.v1.OffsetUnit;
import org.synanton.extraction.v1.ProjectionOutcome;
import org.synanton.extraction.v1.TextNormalization;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Validated canonical-text projection and per-element UTF-16 ranges. */
public final class CanonicalTextEvidence {

    private static final String CONTRACT_ID = "synanton.canonical-text";
    private static final String CONTRACT_VERSION = "1";
    private static final String PROJECTION_ID = "canonical-text";
    private static final String PROJECTION_FORMAT = "text/plain; charset=utf-8";
    private static final String CANONICAL_ENCODING = "UTF-8";
    private static final String CANONICAL_ID_PREFIX = "ct-v1:";
    private static final Pattern CANONICAL_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

    public record Range(int start, int end) {
        public Range {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid canonical range");
            }
        }
    }

    private final String text;
    private final String reason;
    private final Map<String, Range> ranges;

    private CanonicalTextEvidence(String text, String reason, Map<String, Range> ranges) {
        this.text = text;
        this.reason = reason;
        this.ranges = Map.copyOf(ranges);
    }

    public static CanonicalTextEvidence absent() {
        return new CanonicalTextEvidence(null, "CANONICAL_EVIDENCE_UNAVAILABLE", Map.of());
    }

    public static CanonicalTextEvidence from(DocumentPayload payload) {
        if (payload == null) {
            return absent();
        }
        DocumentProjection projection = null;
        for (DocumentProjection candidate : payload.getProjectionsList()) {
            if (PROJECTION_ID.equals(candidate.getProjectionId())
                    || CONTRACT_ID.equals(candidate.getContractId())) {
                if (projection != null) {
                    return invalid("CANONICAL_PROJECTION_AMBIGUOUS");
                }
                projection = candidate;
            }
        }
        if (projection == null || !projection.hasInlineContent()) {
            return absent();
        }
        if (!PROJECTION_ID.equals(projection.getProjectionId())
                || !CONTRACT_ID.equals(projection.getContractId())
                || !CONTRACT_VERSION.equals(projection.getContractVersion())) {
            return invalid("CANONICAL_PROJECTION_CONTRACT_UNSUPPORTED");
        }
        if (!PROJECTION_FORMAT.equals(projection.getFormat())) {
            return invalid("CANONICAL_PROJECTION_FORMAT_UNSUPPORTED");
        }
        if (projection.getOutcome() != ProjectionOutcome.PROJECTION_OUTCOME_SUCCESS_LOSSLESS) {
            return invalid("CANONICAL_PROJECTION_NOT_LOSSLESS");
        }
        String projectionDigest = projection.getDigest();
        if (!isCanonicalDigest(projectionDigest)) {
            return invalid("CANONICAL_PROJECTION_DIGEST_INVALID");
        }
        byte[] bytes = projection.getInlineContent().toByteArray();
        if (!sha256(bytes).equals(projectionDigest.substring("sha256:".length()))) {
            return invalid("CANONICAL_PROJECTION_DIGEST_MISMATCH");
        }
        Optional<String> decoded = decodeUtf8(bytes);
        if (decoded.isEmpty()) {
            return invalid("CANONICAL_PROJECTION_ENCODING_INVALID");
        }

        String canonicalText = decoded.get();
        Map<String, Range> ranges = new HashMap<>();
        CanonicalTextIdentity elementIdentity = null;
        for (DocumentElement element : payload.getElementsList()) {
            if (!element.hasCanonicalTextRange()) {
                continue;
            }
            CanonicalTextRange range = element.getCanonicalTextRange();
            if (!validRange(range, canonicalText.length(), projectionDigest, payload)) {
                return invalid("CANONICAL_ELEMENT_RANGE_INVALID");
            }
            if (elementIdentity == null) {
                elementIdentity = range.getIdentity();
            } else if (!elementIdentity.equals(range.getIdentity())) {
                return invalid("CANONICAL_IDENTITY_MIXED");
            }
            ranges.put(element.getId(), new Range((int) range.getStart(), (int) range.getEnd()));
        }
        return new CanonicalTextEvidence(canonicalText, "", ranges);
    }

    public boolean available() {
        return text != null;
    }

    public String reason() {
        return reason;
    }

    public Optional<Range> range(String elementId) {
        if (elementId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ranges.get(elementId));
    }

    public Optional<String> text(Range range) {
        if (text == null || range.end() > text.length()) {
            return Optional.empty();
        }
        return Optional.of(text.substring(range.start(), range.end()));
    }

    public Optional<String> text(String elementId) {
        return range(elementId).flatMap(this::text);
    }

    private static boolean validRange(CanonicalTextRange range, int textLength,
                                      String projectionDigest, DocumentPayload payload) {
        if (!range.hasIdentity()
                || range.getUnit() != OffsetUnit.OFFSET_UNIT_UTF16_CODE_UNIT
                || range.getNormalization() != TextNormalization.TEXT_NORMALIZATION_NFC
                || range.getLineEndings() != LineEndingPolicy.LINE_ENDING_LF
                || range.getStart() < 0
                || range.getEnd() <= range.getStart()
                || range.getEnd() > textLength) {
            return false;
        }
        CanonicalTextIdentity identity = range.getIdentity();
        String canonicalDigest = identity.getCanonicalTextDigest();
        if (!isCanonicalDigest(canonicalDigest)
                || !canonicalDigest.equals(projectionDigest)
                || !(CANONICAL_ID_PREFIX + canonicalDigest).equals(identity.getCanonicalTextId())
                || !CANONICAL_ENCODING.equals(identity.getEncoding())
                || !CONTRACT_ID.equals(identity.getProjectionContractId())
                || !CONTRACT_VERSION.equals(identity.getProjectionContractVersion())
                || identity.getNormalizationProfile().isBlank()
                || identity.getNormalizationVersion().isBlank()
                || identity.getExtractionExecutionId().isBlank()
                || identity.getSourceDocumentId().isBlank()
                || identity.getSourceDigest().isBlank()
                || identity.getSourceVersion().isBlank()
                || identity.getLineEndings() != LineEndingPolicy.LINE_ENDING_LF
                || identity.getNormalization() != TextNormalization.TEXT_NORMALIZATION_NFC) {
            return false;
        }
        if (!payload.hasIdentity()
                || payload.getIdentity().getDocumentId().isBlank()
                || !identity.getSourceDocumentId().equals(payload.getIdentity().getDocumentId())) {
            return false;
        }
        if (!payload.hasSource()
                || payload.getSource().getSourceDigest().isBlank()
                || payload.getSource().getSourceVersion().isBlank()
                || !identity.getSourceDigest().equals(payload.getSource().getSourceDigest())
                || !identity.getSourceVersion().equals(payload.getSource().getSourceVersion())) {
            return false;
        }
        if (payload.hasProvenance()
                && !payload.getProvenance().getPrimaryExecutionId().isBlank()
                && !identity.getExtractionExecutionId()
                    .equals(payload.getProvenance().getPrimaryExecutionId())) {
            return false;
        }
        return true;
    }

    private static boolean isCanonicalDigest(String digest) {
        return digest != null && CANONICAL_DIGEST.matcher(digest).matches();
    }

    private static Optional<String> decodeUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return Optional.of(decoder.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    private static CanonicalTextEvidence invalid(String reason) {
        return new CanonicalTextEvidence(null, reason, Map.of());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
