package org.synanton.synflux.lifecycle;

/** Tenant and principal established by an authenticated/internal trusted boundary. */
public record VerifiedIngestionContext(String tenantId, String principalId, String principalType) {
    public VerifiedIngestionContext {
        requireText(tenantId, "tenantId");
        requireText(principalId, "principalId");
        requireText(principalType, "principalType");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be provided by a trusted context");
        }
    }
}
