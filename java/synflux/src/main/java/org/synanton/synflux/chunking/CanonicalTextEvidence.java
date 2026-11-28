package org.synanton.synflux.chunking;

import org.synanton.extraction.v1.CanonicalTextRange;
import org.synanton.extraction.v1.DocumentElement;
import org.synanton.extraction.v1.DocumentPayload;
import org.synanton.extraction.v1.DocumentProjection;
import org.synanton.extraction.v1.LineEndingPolicy;
import org.synanton.extraction.v1.OffsetUnit;
import org.synanton.extraction.v1.TextNormalization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/** Validated canonical-text projection and per-element UTF-16 ranges. */
public final class CanonicalTextEvidence {

    private static final String CONTRACT_ID = "synanton.canonical-text";
    private static final String CONTRACT_VERSION = "1";

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
            if (CONTRACT_ID.equals(candidate.getContractId())
                    || "canonical-text".equals(candidate.getProjectionId())) {
                if (projection != null) {
                    return invalid("CANONICAL_PROJECTION_AMBIGUOUS");
                }
                projection = candidate;
            }
        }
        if (projection == null || !projection.hasInlineContent()) {
            return absent();
        }
        if (!CONTRACT_VERSION.equals(projection.getContractVersion())) {
            return invalid("CANONICAL_PROJECTION_VERSION_UNSUPPORTED");
        }
        byte[] bytes = projection.getInlineContent().toByteArray();
        String digest = normalizeDigest(projection.getDigest());
        if (digest.isBlank() || !sha256(bytes).equals(digest)) {
            return invalid("CANONICAL_PROJECTION_DIGEST_MISMATCH");
        }
        String canonicalText = new String(bytes, StandardCharsets.UTF_8);
        Map<String, Range> ranges = new HashMap<>();
        String identityId = null;
        for (DocumentElement element : payload.getElementsList()) {
            CanonicalTextRange range = element.getCanonicalTextRange();
            if (!element.hasCanonicalTextRange()) {
                continue;
            }
            if (!validRange(range, canonicalText.length(), digest, payload)) {
                return invalid("CANONICAL_ELEMENT_RANGE_INVALID");
            }
            if (identityId == null) {
                identityId = range.getIdentity().getCanonicalTextId();
            } else if (!identityId.equals(range.getIdentity().getCanonicalTextId())) {
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
        var identity = range.getIdentity();
        if (identity.getCanonicalTextId().isBlank()
                || normalizeDigest(identity.getCanonicalTextDigest()).isBlank()
                || !normalizeDigest(identity.getCanonicalTextDigest()).equals(projectionDigest)
                || !CONTRACT_ID.equals(identity.getProjectionContractId())
                || !CONTRACT_VERSION.equals(identity.getProjectionContractVersion())) {
            return false;
        }
        if (payload.hasSource()) {
            if (!normalizeDigest(identity.getSourceDigest())
                    .equals(normalizeDigest(payload.getSource().getSourceDigest()))) {
                return false;
            }
            if (!identity.getSourceVersion().isBlank()
                    && !identity.getSourceVersion().equals(payload.getSource().getSourceVersion())) {
                return false;
            }
        }
        return true;
    }

    private static CanonicalTextEvidence invalid(String reason) {
        return new CanonicalTextEvidence(null, reason, Map.of());
    }

    private static String normalizeDigest(String digest) {
        if (digest == null) {
            return "";
        }
        return digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
