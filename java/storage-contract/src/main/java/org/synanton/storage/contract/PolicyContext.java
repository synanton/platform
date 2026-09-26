package org.synanton.storage.contract;

import java.util.Objects;

/**
 * Opaque reference to the Security 1.23 policy under which a request is authorized.
 *
 * <p>The port layer never interprets policy content; it only propagates the reference
 * so adapters and audit trails can bind a decision to the policy revision in force.
 *
 * @param policyId     policy identifier; never blank
 * @param policyRevision policy revision; never blank (pins the evaluated version)
 */
public record PolicyContext(String policyId, String policyRevision) {
    public PolicyContext {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(policyRevision, "policyRevision");
        if (policyId.isBlank() || policyRevision.isBlank()) {
            throw new IllegalArgumentException("policyId and policyRevision must not be blank");
        }
    }

    public static PolicyContext of(String policyId, String policyRevision) {
        return new PolicyContext(policyId, policyRevision);
    }
}
