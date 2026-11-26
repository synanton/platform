package org.synanton.synflux.pipeline.stage;

/** Fail-closed stage error when structured chunking is required but no usable payload exists. */
public class StructuredChunkingRequiredException extends RuntimeException {
    public StructuredChunkingRequiredException(String message) {
        super(message);
    }
}
