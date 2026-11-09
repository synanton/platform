package org.synanton.common.resolution;

/**
 * A graph write was rejected because a newer resolution owns the mention
 * (PR #4c.4 fencing). This is terminal rejected work, not a successful write.
 */
public class StaleResolutionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public StaleResolutionException(String message) {
        super(message);
    }
}
