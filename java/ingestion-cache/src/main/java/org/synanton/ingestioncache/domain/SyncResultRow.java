package org.synanton.ingestioncache.domain;

/** Mutually exclusive final outcomes for the entries in one ingestion manifest. */
public record SyncResultRow(
    int discovered,
    int changed,
    int unchanged,
    int deleted,
    int failed,
    int skipped,
    int quarantined,
    int cancelled
) {
    public SyncResultRow {
        if (discovered < 0 || changed < 0 || unchanged < 0 || deleted < 0
                || failed < 0 || skipped < 0 || quarantined < 0 || cancelled < 0) {
            throw new IllegalArgumentException("SyncResult counters must be non-negative");
        }
    }

    public int entryCount() {
        return discovered + changed + unchanged + deleted + failed + skipped + quarantined + cancelled;
    }
}
