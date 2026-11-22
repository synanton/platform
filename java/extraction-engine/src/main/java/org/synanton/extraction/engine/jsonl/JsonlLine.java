package org.synanton.extraction.engine.jsonl;

import java.util.Map;

/** One syntactically valid JSONL record, with its 1-based line number. */
public record JsonlLine(int lineNumber, String raw, Map<String, Object> value) {}
