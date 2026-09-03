package org.synanton.annotations.domain;

import org.synanton.common.error.SynantonException;

/**
 * Thrown when attempting to edit or re-publish an annotation definition version that
 * has already been published (design §8: "Definitions are immutable once published").
 */
public class AlreadyPublishedException extends SynantonException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    public AlreadyPublishedException(String detail) {
        super(detail, "ERR_ALREADY_PUBLISHED", 409);
    }
}
