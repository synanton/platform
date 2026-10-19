package org.synanton.mcp.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.synanton.mcp.app.McpProperties;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Component
public class McpAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(McpAuthFilter.class);

    private final SecurityProviderClient provider;
    private final Duration ttl;
    private final Duration negativeTtl;
    private final ObjectMapper objectMapper;
    private final Cache<String, CachedIdentity> authCache;

    public McpAuthFilter(SecurityProviderClient provider, McpProperties props) {
        this.provider = provider;
        McpProperties.AuthCache cache = props.authCache();
        this.ttl = Duration.ofSeconds(cache != null && cache.ttlSeconds() > 0 ? cache.ttlSeconds() : 60);
        long negativeSeconds = cache != null && cache.negativeTtlSeconds() != null && cache.negativeTtlSeconds() > 0
                ? cache.negativeTtlSeconds() : 10;
        this.negativeTtl = Duration.ofSeconds(negativeSeconds);
        long maxSize = cache != null && cache.maxSize() > 0 ? cache.maxSize() : 5000;
        this.objectMapper = new ObjectMapper();
        this.authCache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, CachedIdentity>() {
                    @Override
                    public long expireAfterCreate(String key, CachedIdentity value, long currentTime) {
                        return value.ttl().toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, CachedIdentity value, long currentTime, long currentDuration) {
                        return value.ttl().toNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, CachedIdentity value, long currentTime, long currentDuration) {
                        return currentDuration; // read does not extend
                    }
                })
                .maximumSize(maxSize)
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }
        if ("GET".equals(request.getMethod()) && "/mcp".equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        String requestId = UUID.randomUUID().toString();
        request.setAttribute("mcp.request_id", requestId);
        MDC.put("request_id", requestId);
        try {
            String bearer = extractBearer(request);
            if (bearer == null || bearer.isBlank()) {
                reject(response, 401, "AUTHENTICATION_REQUIRED", false, "Authentication required", requestId);
                return;
            }

            String key = AuthCacheKey.of(bearer);
            CachedIdentity hit = authCache.getIfPresent(key);
            if (hit != null) {
                if (hit.identity() == null) {
                    reject(response, 401, "AUTHENTICATION_REQUIRED", false, "Authentication required", requestId);
                    return;
                }
                proceed(hit.identity(), request, response, chain);
                return;
            }

            switch (provider.validate(bearer)) {
                case SecurityProviderClient.Result.Ok ok -> {
                    // TTL is capped by the credential's remaining lifetime: a
                    // cached identity must never outlive the token (#242).
                    authCache.put(key, new CachedIdentity(ok.identity(),
                            effectiveTtl(ttl, ok.identity().expiresAt())));
                    proceed(ok.identity(), request, response, chain);
                }
                case SecurityProviderClient.Result.Invalid ignored -> {
                    authCache.put(key, new CachedIdentity(null, negativeTtl));
                    reject(response, 401, "AUTHENTICATION_REQUIRED", false, "Authentication required", requestId);
                }
                case SecurityProviderClient.Result.Upstream up -> {
                    // 5xx / transport — never cached, fail closed with 503 (not 401):
                    // 401 would tell the client its credential is bad (re-auth loop),
                    // 503 tells it the verifier is down (retry).
                    log.error("security provider unavailable: {}", up.reason());
                    reject(response, 503, "DEPENDENCY_UNAVAILABLE", true, "Authentication service unavailable", requestId);
                }
            }
        } finally {
            MDC.remove("request_id");
            McpIdentityContext.clear();
        }
    }

    private void proceed(McpIdentityContext.Identity id,
                         HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (id.tenantId() == null || id.tenantId().isBlank()) {
            // The provider validated the credential but produced no tenant:
            // fail closed here, where the identity enters (the endpoint used
            // to substitute "demo" — a live hole for null-tenant identities).
            reject(response, 401, "AUTHENTICATION_REQUIRED", false,
                    "Authentication required", UUID.randomUUID().toString());
            return;
        }
        McpIdentityContext.set(id);
        MDC.put("tenant_id", id.tenantId());
        // Contract with McpEndpoint: identity as a node carrying tenantId + scopes.
        ObjectNode node = objectMapper.createObjectNode();
        node.put("tenantId", id.tenantId());
        ArrayNode scopes = node.putArray("scopes");
        id.scopes().forEach(scopes::add);
        request.setAttribute("mcpIdentity", node);
        chain.doFilter(request, response);
    }

    private String extractBearer(HttpServletRequest request) {
        String h = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (h == null) {
            return null;
        }
        return h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).trim() : null;
    }

    /**
     * Identity-cache TTL: the configured bound, capped by the credential's
     * remaining lifetime. An expired (or expiring-now) token yields ZERO so
     * the entry is not cached (#242).
     */
    static java.time.Duration effectiveTtl(java.time.Duration configured, java.time.Instant expiresAt) {
        if (expiresAt == null) {
            return configured;
        }
        java.time.Duration remaining = java.time.Duration.between(java.time.Instant.now(), expiresAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return java.time.Duration.ZERO;
        }
        return remaining.compareTo(configured) < 0 ? remaining : configured;
    }

    private void reject(HttpServletResponse response, int status, String code, boolean retryable,
                        String title, String requestId) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        ObjectNode problem = objectMapper.createObjectNode();
        problem.put("type", "about:blank");
        problem.put("title", title);
        problem.put("status", status);
        problem.put("code", code);
        problem.put("retryable", retryable);
        problem.put("request_id", requestId);
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    private record CachedIdentity(McpIdentityContext.Identity identity, Duration ttl) {}
}
