package org.synanton.relix.identity;

public class MetadataWriteConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MetadataWriteConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
