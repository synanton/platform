package org.synanton.storage.contract;

import java.util.Objects;

/**
 * Reference to a principal (user, service identity, or delegation link) carried
 * inside a {@link SecurityContext}.
 *
 * @param kind       principal kind, e.g. {@code user}, {@code service}, {@code delegation}
 * @param identifier opaque principal identifier within its kind; never blank
 */
public record PrincipalRef(String kind, String identifier) {
    public PrincipalRef {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("identifier must not be blank");
        }
    }

    public static PrincipalRef user(String identifier) {
        return new PrincipalRef("user", identifier);
    }

    public static PrincipalRef service(String identifier) {
        return new PrincipalRef("service", identifier);
    }

    public static PrincipalRef delegation(String identifier) {
        return new PrincipalRef("delegation", identifier);
    }

    public boolean isService() {
        return "service".equals(kind);
    }
}
