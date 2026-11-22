package org.synanton.extraction.engine.jsonl;

/** One rejected JSONL line, with a stable reason and the raw evidence. */
public record JsonlRejectedLine(
        int lineNumber,
        String raw,
        JsonlFailureReason reason,
        String detail) {}
