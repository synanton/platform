package org.synanton.storage.contract;

/**
 * Identity of a single chunk within a document.
 *
 * @param value chunk identifier; never blank
 */
public record ChunkId(String value) {
    public ChunkId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static ChunkId of(String value) {
        return new ChunkId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
