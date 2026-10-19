package org.synanton.common.envelope;

/**
 * Wave 1 placeholder. Rejects every envelope with VERIFIER_UNAVAILABLE.
 *
 * <p>Do not use this implementation in a merged production filter. It
 * exists so the filter and the route classification can be compiled and
 * tested against the interface before the security-provider wire format
 * is specified. A production filter wired to this class would 401 all
 * business routes — that is the correct fail-closed behavior, but it is
 * not a shippable state.
 *
 * <p>Replace with the real verifier once the envelope format is agreed
 * (see ADR-019, Wave 2).
 */
public final class UnwiredEnvelopeVerifier implements EnvelopeVerifier {

    @Override
    public VerifiedSecurityContext verify(String envelope,
                                          String expectedIssuer,
                                          String expectedAudience)
            throws InvalidSecurityContextException {
        throw new InvalidSecurityContextException(RejectionReason.VERIFIER_UNAVAILABLE);
    }
}
