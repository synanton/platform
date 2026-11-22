package org.synanton.extraction.engine.jsonl;

/** Stable reason codes for a rejected JSONL line. */
public enum JsonlFailureReason {
    JSON_PARSE,
    TRUNCATED,
    LINE_TOO_LARGE,
    CONCATENATED_RECORDS,
    SCHEMA,
    UNKNOWN_RECORD_TYPE,
    EMPTY_FIELD,
    UNKNOWN_ENTITY_TYPE,
    UNKNOWN_PREDICATE,
    UNANCHORED,
    SELF_RELATION,
    DUPLICATE
}
