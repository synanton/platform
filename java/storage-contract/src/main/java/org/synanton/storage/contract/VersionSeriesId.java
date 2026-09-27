package org.synanton.storage.contract;

/**
 * Reference to an Ingestion 1.28 version series (Design 1.34). Carried by
 * {@code TemporalExtension}; never interpreted by the storage port itself.
 *
 * @param value version-series identifier; never blank
 */
public record VersionSeriesId(String value) {
    public VersionSeriesId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static VersionSeriesId of(String value) {
        return new VersionSeriesId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
