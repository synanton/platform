package org.synanton.common.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Obtains and caches a short-lived service token via RFC 8693 token exchange.
 * Callers that already hold a long-lived bootstrap JWT can pass it as bootstrapJwt;
 * this provider will transparently refresh before expiry.
 */
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private final String securityBaseUrl;
    private final String bootstrapJwt;
    private final String actorId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt;

    public ServiceTokenProvider(String securityBaseUrl, String bootstrapJwt, String actorId) {
        this.securityBaseUrl = securityBaseUrl;
        this.bootstrapJwt = bootstrapJwt;
        this.actorId = actorId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Schedules worker-token renewal {@code lead} before {@code expiresAt}.
     * Callers replace the local assertion when {@code renewer} returns a fresh token.
     */
    public void scheduleRenewal(Instant expiresAt, Duration lead, Runnable renewer) {
        Duration delay = Duration.between(Instant.now(), expiresAt.minus(lead));
        Duration wait = delay.isNegative() ? Duration.ZERO : delay;
        Thread.ofVirtual().name("worker-token-renewal").start(() -> {
            try {
                Thread.sleep(wait.toMillis());
                renewer.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public synchronized String getToken() {
        if (bootstrapJwt == null || bootstrapJwt.isBlank()) {
            return bootstrapJwt;
        }
        if (cachedToken != null && tokenExpiresAt != null
                && Instant.now().plus(REFRESH_MARGIN).isBefore(tokenExpiresAt)) {
            return cachedToken;
        }
        return refresh();
    }

    private String refresh() {
        try {
            String body = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"
                    + "&subject_token=" + bootstrapJwt
                    + "&subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Aaccess_token"
                    + "&actor_id=" + actorId;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(securityBaseUrl + "/auth/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Token exchange failed with status {}", response.statusCode());
                return bootstrapJwt;
            }

            JsonNode json = objectMapper.readTree(response.body());
            cachedToken = json.get("access_token").asText();
            long expiresIn = json.get("expires_in").asLong(300);
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn);
            log.debug("Service token refreshed for actor {}, expires in {}s", actorId, expiresIn);
            return cachedToken;
        } catch (Exception e) {
            log.warn("Failed to refresh service token for actor {}: {}", actorId, e.getMessage());
            return bootstrapJwt;
        }
    }
}
