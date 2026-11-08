package org.synanton.extraction.engine;

/** A candidate mention; resolution happens later, not in the extractor. */
public record PreEntity(
        String mentionId,
        String surfaceForm,
        String proposedType,
        int charStart,
        int charEnd,
        String context) {}
