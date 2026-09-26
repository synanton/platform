package org.synanton.storage.contract;

/**
 * Projection-generation identifier (Architecture 1.0 invariant 35).
 *
 * <p>Each index build/rebuild produces a new generation fully derived from authoritative
 * Synvault state. Projection mutations are generation-scoped so a rebuild cannot be
 * corrupted by late writes from a previous generation.
 *
 * @param value generation identifier; never blank
 */
public record GenerationId(String value) {
    public GenerationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static GenerationId of(String value) {
        return new GenerationId(value);
    }

    public static GenerationId initial() {
        return new GenerationId("gen-0");
    }

    @Override
    public String toString() {
        return value;
    }
}
