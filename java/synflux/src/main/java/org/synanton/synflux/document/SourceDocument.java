package org.synanton.synflux.document;

import java.util.Objects;

public record SourceDocument(
    String tenantId,
    String sourceId,
    String sourceVersionId,
    String documentId,
    String mediaType,
    byte[] bytes
) {
    public SourceDocument {
        requireText(tenantId, "tenantId");
        requireText(sourceId, "sourceId");
        requireText(sourceVersionId, "sourceVersionId");
        requireText(documentId, "documentId");
        requireText(mediaType, "mediaType");
        Objects.requireNonNull(bytes, "bytes");
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
