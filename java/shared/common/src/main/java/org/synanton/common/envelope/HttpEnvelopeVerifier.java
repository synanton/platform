package org.synanton.common.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Verifies a security-context envelope through the security-provider's
 * documented HTTP surface:
 *
 * <pre>
 * POST /v1/security-context/validate  {"context_token": "..."}
 *   200 {"valid": true,  "security_context": {principal_id, principal_type,
 *        tenant_id, authentication: {method, provider_id, assurance_level,
 *        authenticated_at, expires_at}, ...}}
 *   200 {"valid": false, "reason_code": "EXPIRED|BAD_SIGNATURE|UNKNOWN_TENANT|..."}
 * </pre>
 *
 * <p>Validity is data (HTTP 200 either way), so a non-200 response is
 * {@link EnvelopeVerifier.RejectionReason#VERIFIER_UNAVAILABLE} — mapped by
 * the filter to 503, never 401.
 *
 * <p>The provider rejects tokens smuggling document-handling directives
 * ({@code mask}, {@code represent}, {@code display}, {@code classification},
 * {@code redact}); this consumer mirrors the check so it can never be talked
 * into honoring one if a provider version regresses. A context token
 * authorizes identity, not processing behavior.
 *
 * <p>Audience note (ADR-019 dated correction): the token's {@code aud} is a
 * single shared internal marker and the provider does not enforce a
 * per-service audience. {@code expectedAudience} is carried for parity and
 * logging only; cross-service replay protection rests on the token lifetime
 * and the consumer's authorization checks.
 */
public final class HttpEnvelopeVerifier implements EnvelopeVerifier {

    private static final Set<String> FORBIDDEN_CLAIMS =
            Set.of("mask", "represent", "display", "classification", "redact");

    private final HttpClient http;
    private final URI validateUri;
    private final ObjectMapper mapper;
    private final Duration timeout;

    public HttpEnvelopeVerifier(URI providerBaseUri, Duration timeout) {
        this(providerBaseUri, timeout, HttpClient.newBuilder().connectTimeout(timeout).build(), new ObjectMapper());
    }

    HttpEnvelopeVerifier(URI providerBaseUri, Duration timeout, HttpClient http, ObjectMapper mapper) {
        String base = providerBaseUri.toString().replaceAll("/+$", "");
        this.validateUri = URI.create(base + "/v1/security-context/validate");
        this.timeout = timeout;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public VerifiedSecurityContext verify(String envelope, String expectedIssuer, String expectedAudience)
            throws InvalidSecurityContextException {
        if (envelope == null || envelope.isBlank()) {
            throw new InvalidSecurityContextException(RejectionReason.MISSING_ENVELOPE);
        }
        HttpResponse<String> response;
        try {
            ObjectNode body = mapper.createObjectNode().put("context_token", envelope);
            HttpRequest request = HttpRequest.newBuilder(validateUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InvalidSecurityContextException(RejectionReason.VERIFIER_UNAVAILABLE, interrupted);
        } catch (Exception transport) {
            throw new InvalidSecurityContextException(RejectionReason.VERIFIER_UNAVAILABLE, transport);
        }
        if (response.statusCode() != 200) {
            throw new InvalidSecurityContextException(RejectionReason.VERIFIER_UNAVAILABLE,
                    new IllegalStateException("validate returned HTTP " + response.statusCode()));
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            rejectForbiddenClaims(root);
            if (!root.path("valid").asBoolean(false)) {
                throw new InvalidSecurityContextException(mapReason(root.path("reason_code").asText("")));
            }
            JsonNode context = root.path("security_context");
            String tenantId = context.path("tenant_id").asText("");
            String principalId = context.path("principal_id").asText("");
            if (tenantId.isBlank()) {
                throw new InvalidSecurityContextException(RejectionReason.MISSING_TENANT);
            }
            if (principalId.isBlank()) {
                throw new InvalidSecurityContextException(RejectionReason.MISSING_SUBJECT);
            }
            Instant expiresAt = parseInstant(context.path("authentication").path("expires_at").asText(null));
            return new VerifiedSecurityContext(tenantId, principalId, expectedIssuer, expectedAudience, expiresAt);
        } catch (InvalidSecurityContextException rejection) {
            throw rejection;
        } catch (Exception malformed) {
            throw new InvalidSecurityContextException(RejectionReason.MALFORMED_ENVELOPE, malformed);
        }
    }

    private static RejectionReason mapReason(String reasonCode) {
        return switch (reasonCode == null ? "" : reasonCode.toUpperCase(Locale.ROOT)) {
            case "EXPIRED" -> RejectionReason.EXPIRED;
            case "BAD_SIGNATURE", "INVALID_SIGNATURE" -> RejectionReason.SIGNATURE_INVALID;
            case "UNKNOWN_TENANT" -> RejectionReason.MISSING_TENANT;
            default -> RejectionReason.MALFORMED_ENVELOPE;
        };
    }

    private static void rejectForbiddenClaims(JsonNode root) throws InvalidSecurityContextException {
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (FORBIDDEN_CLAIMS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
                throw new InvalidSecurityContextException(RejectionReason.MALFORMED_ENVELOPE);
            }
        }
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception unparsable) {
            return null;
        }
    }
}
