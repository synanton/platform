package org.synanton.annotations.domain.equalix;

/**
 * Equalix workload classes (design §50). Lower {@link #priority()} is scheduled first;
 * {@code INTERACTIVE}/{@code INCREMENTAL_INGESTION} always rank above the background
 * classes so a large backlog of historical recalculation can never starve them
 * (design §62, Invariant 12).
 */
public enum WorkloadClass {
    INTERACTIVE(0),
    INCREMENTAL_INGESTION(0),
    USER_TRIGGERED_RECALC(1),
    PROJECTION_REBUILD(2),
    ANALYTICS_REBUILD(2),
    HISTORICAL_RECALC(3);

    private final int priority;

    WorkloadClass(int priority) {
        this.priority = priority;
    }

    /** Lower value = scheduled first. */
    public int priority() {
        return priority;
    }
}
