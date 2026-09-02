package org.synanton.annotations.domain.resolutor;

/** The knowledge projections a recalculation plan may leave stale (design §15, §51). */
public enum Projection {
    INDEX,
    VECTOR,
    GRAPH,
    ANALYTICS
}
