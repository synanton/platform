package org.synanton.annotations.domain;

import org.synanton.common.error.SynantonException;

/**
 * Thrown when registering a dependency edge would create a cycle in the
 * annotation dependency DAG (design §10: "Circular dependencies are rejected").
 */
public class CyclicDependencyException extends SynantonException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    public CyclicDependencyException(String detail) {
        super(detail, "ERR_CYCLIC_DEPENDENCY", 400);
    }
}
