package org.synanton.common.envelope;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.synanton.common.tenant.TenantContext;

import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The #252 predicate matrix, against the verified contract:
 * ADR-019 → HTTP validate (200 with valid/reason_code), local X-Tenant-ID
 * cross-check, tenant bound only from the validated context.
 */
class SecurityEnvelopeFilterTest {

    private static final String AUDIENCE = "synanton-internal";
    private static final String ISSUER = "synanton-security-provider";

    private final VerifiedSecurityContext context =
            new VerifiedSecurityContext("tenant-a", "alice", ISSUER, AUDIENCE,
                    Instant.now().plusSeconds(300));

    private SecurityEnvelopeFilter filter(EnvelopeVerifier verifier) {
        return new SecurityEnvelopeFilter(verifier, ISSUER, AUDIENCE, Duration.ofSeconds(60));
    }

    private static MockHttpServletRequest request(String method, String path, String bearer) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        return request;
    }

    private MockHttpServletResponse run(SecurityEnvelopeFilter filter, MockHttpServletRequest request,
                                        AtomicReference<String> boundTenant) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            TenantContext ctx = TenantContext.get();
            boundTenant.set(ctx == null ? null : ctx.tenantId());
        };
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    private MockHttpServletResponse run(SecurityEnvelopeFilter filter, MockHttpServletRequest request) throws Exception {
        return run(filter, request, new AtomicReference<>());
    }

    private EnvelopeVerifier accepting() {
        return (envelope, issuer, audience) -> context;
    }

    private EnvelopeVerifier rejecting(EnvelopeVerifier.RejectionReason reason) {
        return (envelope, issuer, audience) -> {
            throw new InvalidSecurityContextException(reason);
        };
    }

    // 1 — business route, no envelope → 401
    @Test
    void businessRouteWithoutEnvelopeIsRejected() throws Exception {
        assertThat(run(filter(accepting()), request("POST", "/graph/query", null)).getStatus())
                .isEqualTo(401);
    }

    // 2/3/4 — invalid / expired / unknown tenant → 401 (reason logged, not exposed)
    @Test
    void invalidSignatureIsRejected() throws Exception {
        assertThat(run(filter(rejecting(EnvelopeVerifier.RejectionReason.SIGNATURE_INVALID)),
                request("POST", "/graph/query", "tok")).getStatus()).isEqualTo(401);
    }

    @Test
    void expiredEnvelopeIsRejected() throws Exception {
        assertThat(run(filter(rejecting(EnvelopeVerifier.RejectionReason.EXPIRED)),
                request("POST", "/graph/query", "tok")).getStatus()).isEqualTo(401);
    }

    @Test
    void unknownTenantIsRejected() throws Exception {
        assertThat(run(filter(rejecting(EnvelopeVerifier.RejectionReason.MISSING_TENANT)),
                request("POST", "/graph/query", "tok")).getStatus()).isEqualTo(401);
    }

    // 5 — the gap: valid envelope + disagreeing X-Tenant-ID → 403
    @Test
    void tenantHeaderMismatchIsForbidden() throws Exception {
        MockHttpServletRequest request = request("POST", "/graph/query", "tok");
        request.addHeader("X-Tenant-ID", "tenant-b");
        assertThat(run(filter(accepting()), request).getStatus()).isEqualTo(403);
    }

    // 5b — same via the query parameter
    @Test
    void tenantQueryParameterMismatchIsForbidden() throws Exception {
        MockHttpServletRequest request = request("POST", "/graph/query", "tok");
        request.setParameter("tenant_id", "tenant-b");
        assertThat(run(filter(accepting()), request).getStatus()).isEqualTo(403);
    }

    // 6 — valid envelope + matching header → 200, tenant bound from the context
    @Test
    void matchingTenantHeaderPassesAndBindsTheValidatedTenant() throws Exception {
        MockHttpServletRequest request = request("POST", "/graph/query", "tok");
        request.addHeader("X-Tenant-ID", "tenant-a");
        AtomicReference<String> bound = new AtomicReference<>();
        MockHttpServletResponse response = run(filter(accepting()), request, bound);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(bound.get()).isEqualTo("tenant-a");
    }

    // 7 — valid envelope + no header → 200 (absence is not a mismatch)
    @Test
    void absentTenantHeaderPasses() throws Exception {
        AtomicReference<String> bound = new AtomicReference<>();
        MockHttpServletResponse response = run(filter(accepting()), request("POST", "/search", "tok"),
                new AtomicReference<>());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // 9 — every exempt operational route passes without an envelope
    @Test
    void exemptOperationalRoutesPass() throws Exception {
        SecurityEnvelopeFilter filter = filter(rejecting(EnvelopeVerifier.RejectionReason.MISSING_ENVELOPE));
        for (String[] route : List.of(
                new String[]{"GET", "/health"},
                new String[]{"GET", "/readyz"},
                new String[]{"GET", "/livez"},
                new String[]{"GET", "/metrics"},
                new String[]{"GET", "/actuator/health"})) {
            assertThat(run(filter, request(route[0], route[1], null)).getStatus())
                    .as(route[0] + " " + route[1]).isEqualTo(200);
        }
    }

    // 10 — unknown path, no envelope → rejects (not exempt by prefix)
    @Test
    void unknownPathIsNotExempt() throws Exception {
        assertThat(run(filter(accepting()), request("GET", "/healthz-typo", null)).getStatus())
                .isEqualTo(401);
    }

    // 11 — MCP business operations are not exempt (the MCP filter owns /mcp)
    @Test
    void mcpBusinessOperationIsNotExempt() throws Exception {
        assertThat(run(filter(accepting()), request("POST", "/mcp", null)).getStatus())
                .isEqualTo(401);
    }

    // 12 — valid envelope, tenant-scoped call → context bound, request proceeds
    @Test
    void validEnvelopeBindsTheContextForTenantScopedWork() throws Exception {
        AtomicReference<String> bound = new AtomicReference<>();
        MockHttpServletResponse response = run(filter(accepting()), request("POST", "/graph/query", "tok"), bound);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(bound.get()).isEqualTo("tenant-a");
    }

    // Verifier outage is a dependency failure (503), not an authentication failure.
    @Test
    void verifierUnavailableReturns503() throws Exception {
        assertThat(run(filter(rejecting(EnvelopeVerifier.RejectionReason.VERIFIER_UNAVAILABLE)),
                request("POST", "/graph/query", "tok")).getStatus()).isEqualTo(503);
    }
}
