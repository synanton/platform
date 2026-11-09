package org.synanton.synquest.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.synanton.common.jwt.ServiceTokenMinter;
import org.synanton.common.resolution.ContractViolationException;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.common.resolution.StaleResolutionException;

/**
 * HTTP port for {@code POST /graph/resolutions}. HTTP 409 is a stale fence
 * (terminal rejected work), 4xx is a contract violation, and 5xx/IO are
 * retryable infrastructure failures. No other outcome is inferred.
 */
public final class HttpRelixResolutionWriter implements ResolutionWriteClient {

    private final HttpClient client;
    private final String baseUrl;
    private final ServiceTokenMinter tokenMinter;
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public HttpRelixResolutionWriter(String baseUrl, ServiceTokenMinter tokenMinter) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.tokenMinter = tokenMinter;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public void upsertResolution(ResolutionDecision decision) {
        String body;
        try {
            body = mapper.writeValueAsString(decision);
        } catch (Exception e) {
            throw new ContractViolationException("Could not serialize resolution decision", e);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/graph/resolutions"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer "
                        + tokenMinter.mint(decision.tenantId(), "synquest-resolution-consumer"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableProcessingException("Resolution graph write interrupted", e);
        } catch (Exception e) {
            throw new RetryableProcessingException("Resolution graph write failed", e);
        }
        int status = response.statusCode();
        if (status / 100 == 2) {
            return;
        }
        if (status == 409) {
            throw new StaleResolutionException("Relix rejected stale resolution: " + response.body());
        }
        if (status / 100 == 4) {
            throw new ContractViolationException("Relix rejected resolution HTTP " + status
                    + ": " + response.body());
        }
        throw new RetryableProcessingException("Relix resolution write returned HTTP " + status
                + ": " + response.body());
    }
}
