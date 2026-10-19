package org.synanton.common.envelope;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP verifier against the security-provider's documented response
 * shape: validity is data (HTTP 200 always), reason codes are machine
 * readable, and the consumer mirrors the claim-smuggling rejection.
 */
class HttpEnvelopeVerifierTest {

    private HttpServer server;
    private URI base;
    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/security-context/validate", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpEnvelopeVerifier verifier() {
        return new HttpEnvelopeVerifier(base, Duration.ofSeconds(2));
    }

    private static String validContext(String tenantId, String expiresAt) {
        return "{\"valid\":true,\"security_context\":{"
                + "\"principal_id\":\"alice\",\"principal_type\":\"USER\","
                + "\"tenant_id\":\"" + tenantId + "\","
                + "\"authentication\":{\"method\":\"JWT\",\"provider_id\":\"p\","
                + "\"assurance_level\":\"HIGH\",\"authenticated_at\":\"2026-10-19T00:00:00Z\","
                + "\"expires_at\":\"" + expiresAt + "\"}}}";
    }

    @Test
    void validResponseYieldsTheVerifiedContext() throws Exception {
        body = validContext("tenant-a", "2026-10-19T00:10:00Z");
        VerifiedSecurityContext context = verifier().verify("tok", "iss", "synanton-internal");
        assertThat(context.tenantId()).isEqualTo("tenant-a");
        assertThat(context.subjectId()).isEqualTo("alice");
    }

    @Test
    void expiredReasonMaps() {
        status = 200;
        body = "{\"valid\":false,\"reason_code\":\"EXPIRED\"}";
        assertThatThrownBy(() -> verifier().verify("tok", "iss", "aud"))
                .isInstanceOfSatisfying(InvalidSecurityContextException.class,
                        e -> assertThat(e.reason()).isEqualTo(EnvelopeVerifier.RejectionReason.EXPIRED));
    }

    @Test
    void badSignatureReasonMaps() {
        body = "{\"valid\":false,\"reason_code\":\"BAD_SIGNATURE\"}";
        assertThatThrownBy(() -> verifier().verify("tok", "iss", "aud"))
                .isInstanceOfSatisfying(InvalidSecurityContextException.class,
                        e -> assertThat(e.reason()).isEqualTo(EnvelopeVerifier.RejectionReason.SIGNATURE_INVALID));
    }

    @Test
    void unknownTenantReasonMaps() {
        body = "{\"valid\":false,\"reason_code\":\"UNKNOWN_TENANT\"}";
        assertThatThrownBy(() -> verifier().verify("tok", "iss", "aud"))
                .isInstanceOfSatisfying(InvalidSecurityContextException.class,
                        e -> assertThat(e.reason()).isEqualTo(EnvelopeVerifier.RejectionReason.MISSING_TENANT));
    }

    @Test
    void smuggledClaimIsRejectedByTheConsumerEvenIfTheProviderRegresses() {
        body = validContext("tenant-a", "2026-10-19T00:10:00Z").replace("{\"valid\":true",
                "{\"mask\":\"redact-everything\",\"valid\":true");
        assertThatThrownBy(() -> verifier().verify("tok", "iss", "aud"))
                .isInstanceOfSatisfying(InvalidSecurityContextException.class,
                        e -> assertThat(e.reason()).isEqualTo(EnvelopeVerifier.RejectionReason.MALFORMED_ENVELOPE));
    }

    @Test
    void providerErrorIsUnavailableNotUnauthorized() {
        status = 500;
        body = "{\"error\":\"boom\"}";
        assertThatThrownBy(() -> verifier().verify("tok", "iss", "aud"))
                .isInstanceOfSatisfying(InvalidSecurityContextException.class,
                        e -> assertThat(e.reason()).isEqualTo(EnvelopeVerifier.RejectionReason.VERIFIER_UNAVAILABLE));
    }

    @Test
    void missingTenantInAValidResponseIsRejected() {
        body = "{\"valid\":true,\"security_context\":{\"principal_id\":\"alice\",\"principal_type\":\"USER\"}}";
        assertThatThrownBy(() -> verifier().verify("tok", "iss", "aud"))
                .isInstanceOfSatisfying(InvalidSecurityContextException.class,
                        e -> assertThat(e.reason()).isEqualTo(EnvelopeVerifier.RejectionReason.MISSING_TENANT));
    }
}
