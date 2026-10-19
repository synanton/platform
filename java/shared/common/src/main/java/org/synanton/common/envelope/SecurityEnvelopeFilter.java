package org.synanton.common.envelope;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.synanton.common.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The downstream trust predicate (ADR-019 / #252) for internal services.
 *
 * <ol>
 *   <li>Envelope-exempt routes pass ({@link EnvelopeExemptRoutes}) — exact
 *       method/path allowlist, no prefix matching.</li>
 *   <li>No {@code Authorization: Bearer} → 401.</li>
 *   <li>Validate through the security-provider; invalid → 401 (the
 *       reason code is logged, never exposed). Verifier unavailable → 503.</li>
 *   <li>Inbound {@code X-Tenant-ID} header or {@code ?tenant_id=} that
 *       disagrees with the validated context → 403 (the behavior the
 *       provider's Go middleware has always had; the Java consumers lacked
 *       it).</li>
 *   <li>The validated tenant is bound to the request context — never read
 *       from the header.</li>
 * </ol>
 *
 * <p>The identity cache is bounded by the configured TTL and by the
 * envelope's remaining lifetime (#242's pattern), so a cached identity
 * never outlives its credential.
 */
public final class SecurityEnvelopeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityEnvelopeFilter.class);
    private static final int MAX_CACHE_ENTRIES = 10_000;

    private final EnvelopeVerifier verifier;
    private final String expectedIssuer;
    private final String expectedAudience;
    private final Duration cacheTtl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SecurityEnvelopeFilter(EnvelopeVerifier verifier, String expectedIssuer,
                                  String expectedAudience, Duration cacheTtl) {
        this.verifier = verifier;
        this.expectedIssuer = expectedIssuer;
        this.expectedAudience = expectedAudience;
        this.cacheTtl = cacheTtl;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (EnvelopeExemptRoutes.isExempt(request.getMethod(), request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String bearer = bearerOf(request.getHeader("Authorization"));
        if (bearer == null) {
            reject(response, 401, "AUTHENTICATION_REQUIRED");
            return;
        }
        VerifiedSecurityContext context;
        try {
            context = cachedOrVerify(bearer);
        } catch (InvalidSecurityContextException rejection) {
            if (rejection.reason() == EnvelopeVerifier.RejectionReason.VERIFIER_UNAVAILABLE) {
                log.warn("security-context verifier unavailable: {}", rejection.reason());
                reject(response, 503, "DEPENDENCY_UNAVAILABLE");
            } else {
                log.warn("security-context rejected: {}", rejection.reason());
                reject(response, 401, "AUTHENTICATION_REQUIRED");
            }
            return;
        }
        String headerTenant = firstNonBlank(
                request.getHeader("X-Tenant-ID"),
                request.getParameter("tenant_id"));
        if (headerTenant != null && !headerTenant.equals(context.tenantId())) {
            log.warn("tenant hint '{}' disagrees with validated tenant '{}'", headerTenant, context.tenantId());
            reject(response, 403, "TENANT_DENIED");
            return;
        }
        TenantContext.setAnonymous(context.tenantId());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private VerifiedSecurityContext cachedOrVerify(String bearer) throws InvalidSecurityContextException {
        String key = sha256(bearer);
        Instant now = Instant.now();
        Cached hit = cache.get(key);
        if (hit != null && hit.validUntil().isAfter(now)) {
            return hit.context();
        }
        VerifiedSecurityContext context = verifier.verify(bearer, expectedIssuer, expectedAudience);
        Duration ttl = effectiveTtl(cacheTtl, context.expiresAt());
        if (!ttl.isZero() && !ttl.isNegative()) {
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(key, new Cached(context, now.plus(ttl)));
        }
        return context;
    }

    /** Configured bound capped by the envelope's remaining lifetime. */
    static Duration effectiveTtl(Duration configured, Instant expiresAt) {
        if (expiresAt == null) {
            return configured;
        }
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ZERO;
        }
        return remaining.compareTo(configured) < 0 ? remaining : configured;
    }

    private static String bearerOf(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\"}");
    }

    private record Cached(VerifiedSecurityContext context, Instant validUntil) {}
}
