package org.synanton.security.envelope;

/**
 * Thrown when envelope verification fails. Carries a structured
 * {@link EnvelopeVerifier.RejectionReason} for server-side logging.
 * The reason is never exposed to clients.
 */
public class InvalidSecurityContextException extends Exception {
    private final EnvelopeVerifier.RejectionReason reason;

    public InvalidSecurityContextException(EnvelopeVerifier.RejectionReason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public InvalidSecurityContextException(EnvelopeVerifier.RejectionReason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public EnvelopeVerifier.RejectionReason reason() {
        return reason;
    }
}
