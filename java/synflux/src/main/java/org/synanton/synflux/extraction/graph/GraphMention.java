package org.synanton.synflux.extraction.graph;

import java.time.Instant;

/** Wire shape for {@code POST /graph/mentions} (matches Relix's ExtractedMention). */
public record GraphMention(
        String mentionId,
        String tenantId,
        String sourceVersionId,
        String contentRefId,
        String chunkId,
        int chunkOffsetStart,
        int chunkOffsetEnd,
        int documentOffsetStart,
        int documentOffsetEnd,
        String surfaceForm,
        String proposedType,
        Instant extractedAt) {}
