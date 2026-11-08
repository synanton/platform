package org.synanton.relix.graph;

import java.time.Instant;

/** A pre-resolution mention persisted in the isolated extracted-mention graph. */
public record ExtractedMention(
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
