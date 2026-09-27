package org.synanton.storage.contract;

import java.util.Objects;

/**
 * Port-level storage failure. Carries a {@link StorageErrorKind} so domain code can
 * branch on failure semantics without importing any backend-specific exception type.
 *
 * <p>{@link Provisional} {@code 1.32}: see {@link StorageErrorKind}.
 */
@Provisional(value = "1.32", reason = "Error shape binds to the 1.32 Operation/error contract at freeze")
public class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final StorageErrorKind kind;
    private final boolean retryable;

    public StorageException(StorageErrorKind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.retryable = kind == StorageErrorKind.TRANSIENT;
    }

    public StorageException(StorageErrorKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.retryable = kind == StorageErrorKind.TRANSIENT;
    }

    public StorageErrorKind kind() {
        return kind;
    }

    public boolean retryable() {
        return retryable;
    }

    public static StorageException notFound(String message) {
        return new StorageException(StorageErrorKind.NOT_FOUND, message);
    }

    public static StorageException conflict(String message) {
        return new StorageException(StorageErrorKind.CONFLICT, message);
    }

    public static StorageException unsupported(String message) {
        return new StorageException(StorageErrorKind.UNSUPPORTED, message);
    }
}
