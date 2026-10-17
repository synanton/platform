package org.synanton.security.envelope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnwiredEnvelopeVerifierTest {

    private final UnwiredEnvelopeVerifier verifier = new UnwiredEnvelopeVerifier();

    @Test
    void anyEnvelope_rejectsWithVerifierUnavailable() {
        assertThatThrownBy(() -> verifier.verify("anything", "issuer", "audience"))
            .isInstanceOf(InvalidSecurityContextException.class)
            .extracting(e -> ((InvalidSecurityContextException) e).reason())
            .isEqualTo(EnvelopeVerifier.RejectionReason.VERIFIER_UNAVAILABLE);
    }

    @Test
    void nullEnvelope_alsoRejects() {
        assertThatThrownBy(() -> verifier.verify(null, "issuer", "audience"))
            .isInstanceOf(InvalidSecurityContextException.class);
    }

    @Test
    void emptyEnvelope_alsoRejects() {
        assertThatThrownBy(() -> verifier.verify("", "issuer", "audience"))
            .isInstanceOf(InvalidSecurityContextException.class);
    }
}
