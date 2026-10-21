package org.synanton.ingestioncache.domain;

/** A workflow id is already bound to a different operation/run/source/resource. */
public class WorkflowIdentityConflictException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    public WorkflowIdentityConflictException(String message) {
        super(message);
    }
}
