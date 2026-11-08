package org.synanton.synquest.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.synanton.common.jwt.ServiceTokenMinter;
import org.synanton.common.resolution.CanonicalEntityLookup;
import org.synanton.common.resolution.RetryableProcessingException;

/** HTTP client for Relix's canonical entity verification endpoint. */
public final class HttpRelixCanonicalEntityLookup implements CanonicalEntityLookup {

    private final HttpClient client;
    private final String baseUrl;
    private final ServiceTokenMinter tokenMinter;
    private final ObjectMapper mapper;

    public HttpRelixCanonicalEntityLookup(String baseUrl, ServiceTokenMinter tokenMinter, ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.tokenMinter = tokenMinter;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public Optional<String> getActiveEntityType(String tenantId, String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return Optional.empty();
        }
        try {
            String encoded = URLEncoder.encode(entityId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/v1/entities/" + encoded + "/type"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + tokenMinter.mint(tenantId, "synquest-resolution"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() / 100 != 2) {
                throw new RetryableProcessingException("Canonical entity lookup returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            Map<String, Object> body = mapper.readValue(response.body(), Map.class);
            Object type = body.get("type");
            return type == null ? Optional.empty() : Optional.of(String.valueOf(type));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableProcessingException("Canonical entity lookup interrupted", e);
        } catch (RetryableProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableProcessingException(
                    "Canonical entity lookup failed for " + entityId, e);
        }
    }
}
