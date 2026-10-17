package org.synanton.security.envelope;

/**
 * Verifies a downstream security envelope cryptographically before any
 * claim is trusted. Implementations must check, in order:
 * <ol>
 *   <li>signature against the security-provider's verification key</li>
 *   <li>iss == expectedIssuer</li>
 *   <li>aud contains expectedAudience</li>
 *   <li>exp / iat within the agreed lifetime and clock skew</li>
 *   <li>tenant_id and subject_id present and non-blank</li>
 * </ol>
 * Only if all pass, return a {@link VerifiedSecurityContext}.
 */
public interface EnvelopeVerifier {

    VerifiedSecurityContext verify(String envelope,
                                   String expectedIssuer,
                                   String expectedAudience)
            throws InvalidSecurityContextException;

    /** Package-visible reason enum for logging. Never exposed to clients. */
    enum RejectionReason {
        MISSING_ENVELOPE,
        MALFORMED_ENVELOPE,
        SIGNATURE_INVALID,
        ISSUER_MISMATCH,
        AUDIENCE_MISMATCH,
        EXPIRED,
        NOT_YET_VALID,
        MISSING_TENANT,
        MISSING_SUBJECT,
        VERIFIER_UNAVAILABLE
    }
}
