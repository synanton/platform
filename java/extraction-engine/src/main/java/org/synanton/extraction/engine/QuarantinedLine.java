package org.synanton.extraction.engine;

/**
 * One rejected or quarantined JSONL line.
 *
 * <p>The line number is 1-based relative to the model response, not the source
 * document. Raw text is preserved so a reviewer can replay the failure without
 * guessing at the original framing.
 *
 * <p>{@code category} is the remediation class from {@code JsonlFailureReason}
 * ({@code STRUCTURAL} or {@code SEMANTIC}). This record is not persisted in
 * P0-1; when consumer persistence lands, quarantine rows must be replaced per
 * extraction generation rather than appended as orphans.
 */
public record QuarantinedLine(
        int lineNumber,
        String raw,
        String reasonCode,
        String detail,
        String category) {}
