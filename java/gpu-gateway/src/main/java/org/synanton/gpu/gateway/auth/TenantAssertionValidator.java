package org.synanton.gpu.gateway.auth;

import org.springframework.stereotype.Component;

// TenantAssertionValidator enforces the invariant:
//   tenant_id is an authorization context assertion, not an authentication credential.
//
// The authenticated service identity is established independently by mTLS at the transport layer.
// This validator checks that the asserted tenant_id is non-empty and (in production) falls within
// the calling service principal's permitted tenant scope as configured in the Gateway's policy store.
//
// Initial implementation: validates that tenant_id is present and non-blank.
// Production extension: cross-reference tenantId against the mTLS CN's permitted scope list.
@Component
public class TenantAssertionValidator {

    public ValidationResult validate(String tenantId, String serviceIdentity) {
        if (tenantId == null || tenantId.isBlank()) {
            return ValidationResult.failure("tenant_id is required");
        }
        if (tenantId.length() > 255) {
            return ValidationResult.failure("tenant_id exceeds maximum length of 255");
        }
        // Production: verify that serviceIdentity (mTLS CN) is authorized to assert this tenantId.
        // Omitted in initial implementation; all authenticated callers are trusted for any tenant.
        return ValidationResult.success();
    }

    public record ValidationResult(boolean success, String reason) {
        public static ValidationResult success() { return new ValidationResult(true, null); }
        public static ValidationResult failure(String reason) { return new ValidationResult(false, reason); }
    }
}
