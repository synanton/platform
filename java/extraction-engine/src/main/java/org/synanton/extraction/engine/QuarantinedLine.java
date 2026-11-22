package org.synanton.extraction.engine;

/**
 * One rejected or quarantined JSONL line.
 *
 * <p>The line number is 1-based relative to the model response, not the source
 * document. Raw text is preserved so a reviewer can replay the failure without
 * guessing at the original framing.
 */
public record QuarantinedLine(
        int lineNumber,
        String raw,
        String reasonCode,
        String detail) {}
