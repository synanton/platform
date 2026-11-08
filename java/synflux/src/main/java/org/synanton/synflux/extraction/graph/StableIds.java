package org.synanton.synflux.extraction.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic RAKG graph ids; model-generated ids are never trusted. */
public final class StableIds {

    private StableIds() {
    }

    public static String mentionId(String tenantId, String sourceVersionId, String chunkId,
                                   int chunkOffsetStart, int chunkOffsetEnd, String surfaceForm) {
        return sha256(tenantId + "|" + sourceVersionId + "|" + chunkId + "|" + chunkOffsetStart
                + "|" + chunkOffsetEnd + "|" + surfaceForm);
    }

    public static String relationId(String tenantId, String sourceVersionId,
                                    String subjectMentionId, String predicate, String objectMentionId) {
        return sha256(tenantId + "|" + sourceVersionId + "|" + subjectMentionId
                + "|" + predicate + "|" + objectMentionId);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
