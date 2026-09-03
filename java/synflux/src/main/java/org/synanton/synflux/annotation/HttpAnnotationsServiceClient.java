package org.synanton.synflux.annotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class HttpAnnotationsServiceClient implements AnnotationsServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAnnotationsServiceClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpAnnotationsServiceClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public UUID startProcessingRun(
            String producer, String producerVersion, String tenantId,
            String definitionId, Integer definitionVersion, String scope
    ) {
        Map<String, Object> body = new HashMap<>();
        body.put("producer", producer);
        body.put("producerVersion", producerVersion);
        body.put("tenantId", tenantId);
        body.put("definitionId", definitionId);
        body.put("definitionVersion", definitionVersion);
        body.put("scope", scope);
        JsonNode response = post(baseUrl + "/processing-runs", body);
        return UUID.fromString(response.get("processingRunId").asText());
    }

    @Override
    public void completeProcessingRun(UUID processingRunId, String status, String errorSummary) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        body.put("errorSummary", errorSummary);
        patch(baseUrl + "/processing-runs/" + processingRunId, body);
    }

    private JsonNode post(String url, Object body) {
        return send(url, "POST", body);
    }

    private JsonNode patch(String url, Object body) {
        return send(url, "PATCH", body);
    }

    private JsonNode send(String url, String method, Object body) {
        try {
            String payload = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("{} {} -> {}", method, url, response.statusCode());
            if (response.statusCode() >= 300) {
                throw new AnnotationsServiceException(
                        "annotations service " + method + " " + url + " failed: HTTP " + response.statusCode() + " " + response.body());
            }
            return response.body() == null || response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        } catch (IOException e) {
            throw new AnnotationsServiceException("annotations service " + method + " " + url + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnnotationsServiceException("annotations service " + method + " " + url + " interrupted", e);
        }
    }

    public static class AnnotationsServiceException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public AnnotationsServiceException(String message) {
            super(message);
        }

        public AnnotationsServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
