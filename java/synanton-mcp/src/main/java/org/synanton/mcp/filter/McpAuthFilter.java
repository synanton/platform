package org.synanton.mcp.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.synanton.mcp.app.McpProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class McpAuthFilter extends OncePerRequestFilter {
    private final String securityUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, JsonNode> authCache;

    public McpAuthFilter(McpProperties props) {
        this.securityUrl = props.securityUrl();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.objectMapper = new ObjectMapper();
        long ttl = (props.authCache() != null) ? props.authCache().ttlSeconds() : 60;
        long maxSize = (props.authCache() != null) ? props.authCache().maxSize() : 5000;
        this.authCache = Caffeine.newBuilder()
            .expireAfterWrite(ttl, TimeUnit.SECONDS)
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
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            writeMcpAuthError(response, "Authorization required");
            return;
        }
        JsonNode identity = authCache.getIfPresent(cacheKey(authHeader));
        if (identity == null) {
            try {
                HttpRequest secRequest = HttpRequest.newBuilder()
                    .uri(URI.create(securityUrl + "/auth/validate-header"))
                    .header("Authorization", authHeader)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();
                HttpResponse<String> secResponse = httpClient.send(secRequest, HttpResponse.BodyHandlers.ofString());
                if (secResponse.statusCode() != 200) {
                    writeMcpAuthError(response, "Invalid or revoked API key");
                    return;
                }
                identity = objectMapper.readTree(secResponse.body());
                authCache.put(cacheKey(authHeader), identity);
            } catch (Exception e) {
                writeMcpAuthError(response, "Security service unavailable");
                return;
            }
        }
        request.setAttribute("mcpIdentity", identity);
        chain.doFilter(request, response);
    }

    /**
     * Cache key for a bearer token. The raw bearer must never sit in the cache
     * as a key (heap-dump exposure, blocker 2f) — the entry is addressed by
     * SHA-256 of the token instead. Same identity, no behavior change beyond
     * the key.
     */
    static String cacheKey(String authHeader) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(authHeader.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void writeMcpAuthError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"" + message + "\"}}");
    }
}
