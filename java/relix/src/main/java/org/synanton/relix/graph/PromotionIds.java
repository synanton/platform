package org.synanton.relix.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.synanton.common.resolution.CanonicalRelation;

/** Deterministic semantic and evidence identities (policy-version independent). */
public final class PromotionIds {

    private PromotionIds() {
    }

    public static String canonicalRelationId(CanonicalRelation relation) {
        String predicate = normalizePredicate(relation.predicate());
        return sha256(relation.tenantId() + "|" + relation.subjectEntityId() + "|"
                + predicate + "|" + relation.objectEntityId());
    }

    public static String evidenceId(CanonicalRelation relation) {
        return sha256(relation.tenantId() + "|" + relation.sourceVersionId() + "|"
                + relation.extractedRelationId() + "|" + relation.evidenceChunkId() + "|"
                + relation.evidenceChunkOffsetStart() + "|" + relation.evidenceChunkOffsetEnd());
    }

    public static String normalizePredicate(String predicate) {
        String normalized = predicate == null ? "" : predicate.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? "related_to" : normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
