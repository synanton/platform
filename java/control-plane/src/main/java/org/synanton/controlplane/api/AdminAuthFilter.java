package org.synanton.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.synanton.controlplane.app.ControlPlaneProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AdminAuthFilter extends OncePerRequestFilter {

    private final String securityUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AdminAuthFilter(ControlPlaneProperties props) {
        this.securityUrl = props.securityUrl();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (path.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            sendError(response, 401, "Authorization header required");
            return;
        }

        try {
            HttpRequest secRequest = HttpRequest.newBuilder()
                    .uri(URI.create(securityUrl + "/auth/validate-header"))
                    .header("Authorization", authHeader)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> secResponse = httpClient.send(secRequest, HttpResponse.BodyHandlers.ofString());

            if (secResponse.statusCode() != 200) {
                sendError(response, 401, "Invalid credentials");
                return;
            }

            JsonNode identity = objectMapper.readTree(secResponse.body());

            // Phase 3 auth logic: allow valid JWTs or any token with "admin" in scopes.
            // JWT callers are trusted as admin for Phase 3; API key callers need the admin scope.
            String identityProfile = identity.path("identityProfile").asText("");
            boolean isJwt = "JWT".equalsIgnoreCase(identityProfile);

            boolean hasAdminScope = false;
            JsonNode scopesNode = identity.path("scopes");
            if (scopesNode.isArray()) {
                for (JsonNode s : scopesNode) {
                    if ("admin".equals(s.asText())) {
                        hasAdminScope = true;
                        break;
                    }
                }
            }

            if (!isJwt && !hasAdminScope) {
                sendError(response, 403, "admin scope required");
                return;
            }

            request.setAttribute("identity", identity);
            chain.doFilter(request, response);

        } catch (Exception e) {
            sendError(response, 503, "Security service unavailable");
        }
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
