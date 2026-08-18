package org.synanton.controlplane.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.controlplane.app.ControlPlaneProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpService {

    private static final Logger log = LoggerFactory.getLogger(HttpService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final int readTimeoutMs;

    public HttpService(ControlPlaneProperties props) {
        int connectMs = (props.httpClient() != null) ? props.httpClient().connectTimeoutMs() : 2000;
        this.readTimeoutMs = (props.httpClient() != null) ? props.httpClient().readTimeoutMs() : 5000;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public JsonNode post(String url, String bodyJson, String bearerToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("POST {} -> {}", url, response.statusCode());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Upstream error " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * POST with a full Authorization header value (e.g. "Bearer token" or "ApiKey key") instead of
     * a bare token. Used when forwarding the caller's credential as-is.
     */
    public JsonNode postWithAuthHeader(String url, String bodyJson, String authHeader) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("POST {} -> {}", url, response.statusCode());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Upstream error " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    public JsonNode get(String url, String bearerToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .timeout(Duration.ofMillis(readTimeoutMs))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return null;
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Upstream error " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * GET with a full Authorization header value forwarded from the caller.
     */
    public JsonNode getWithAuthHeader(String url, String authHeader) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .GET()
                .timeout(Duration.ofMillis(readTimeoutMs))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return null;
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Upstream error " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    public void put(String url, String bodyJson, String bearerToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .PUT(HttpRequest.BodyPublishers.ofString(bodyJson))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Upstream error " + response.statusCode() + ": " + response.body());
        }
    }

    /**
     * PUT with a full Authorization header value forwarded from the caller.
     */
    public void putWithAuthHeader(String url, String bodyJson, String authHeader) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader)
                .PUT(HttpRequest.BodyPublishers.ofString(bodyJson))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Upstream error " + response.statusCode() + ": " + response.body());
        }
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }
}
