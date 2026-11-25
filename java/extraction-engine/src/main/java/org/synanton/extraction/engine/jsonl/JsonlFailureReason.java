package org.synanton.extraction.engine.jsonl;

/** Stable reason codes for a rejected JSONL line, grouped by remediation class. */
public enum JsonlFailureReason {
    JSON_PARSE(Category.STRUCTURAL),
    TRUNCATED(Category.STRUCTURAL),
    LINE_TOO_LARGE(Category.STRUCTURAL),
    CONCATENATED_RECORDS(Category.STRUCTURAL),
    SCHEMA(Category.STRUCTURAL),
    EMPTY_FIELD(Category.STRUCTURAL),
    UNKNOWN_RECORD_TYPE(Category.STRUCTURAL),
    UNKNOWN_ENTITY_TYPE(Category.SEMANTIC),
    UNKNOWN_PREDICATE(Category.SEMANTIC),
    DOMAIN_RANGE(Category.SEMANTIC),
    UNANCHORED(Category.SEMANTIC),
    SELF_RELATION(Category.SEMANTIC),
    DUPLICATE(Category.SEMANTIC);

    public enum Category {
        /** Framing/serialization/schema failure; fix with prompt or enforcement. */
        STRUCTURAL,
        /** Model drifted from the ontology/anchoring contract; fix with ontology work. */
        SEMANTIC
    }

    private final Category category;

    JsonlFailureReason(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
