package org.synanton.storage.contract;

/**
 * Identity of a canonical knowledge document. Opaque to storage backends;
 * tenant isolation is enforced via {@link SecurityContext}, not the id itself.
 *
 * @param value document identifier; never blank
 */
public record DocumentId(String value) {
    public DocumentId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static DocumentId of(String value) {
        return new DocumentId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
