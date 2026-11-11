package org.synanton.synflux.extraction.graph;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

/** Wire shape for {@code POST /graph/mentions} (matches Relix's ExtractedMention). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
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
