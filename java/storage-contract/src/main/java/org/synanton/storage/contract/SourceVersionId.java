package org.synanton.storage.contract;

/**
 * Reference to an Ingestion 1.28 source version. Owned semantically by Ingestion 1.28;
 * persisted here only as a domain field (proposal §11.1, Design 1.34).
 *
 * @param value source-version identifier; never blank
 */
public record SourceVersionId(String value) {
    public SourceVersionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static SourceVersionId of(String value) {
        return new SourceVersionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
