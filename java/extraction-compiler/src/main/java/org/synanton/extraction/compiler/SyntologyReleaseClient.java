package org.synanton.extraction.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Read-only production API client. The compiler never reads Syntology's database or Jena store
 * directly.
 */
public final class SyntologyReleaseClient {
    private final URI baseUri;
    private final String tenantId;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public SyntologyReleaseClient(String baseUrl, String tenantId, String token) {
        this(baseUrl, tenantId, token, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build());
    }

    SyntologyReleaseClient(String baseUrl, String tenantId, String token, HttpClient http) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.tenantId = require(tenantId, "tenantId");
        this.token = require(token, "token");
        this.http = Objects.requireNonNull(http, "http");
    }

    /** Immutable lookup by releaseId; never consults the active-ontology alias. */
    public ReleaseIdentity resolveRelease(String releaseId) {
        JsonNode body = get("/v1/tenants/" + path(tenantId)
            + "/ontology-releases/" + path(require(releaseId, "releaseId")));
        return new ReleaseIdentity(
            text(body, "tenantId"), text(body, "ontologyId"), text(body, "version"),
            text(body, "releaseId"), text(body, "releaseDigest"));
    }

    public JsonNode fetchIr(String version) {
        return get("/api/v1/admin/ontology/schemas/" + path(version));
    }

    private JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-ID", tenantId)
            .header("Accept", "application/json")
            .GET()
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Syntology " + path + " returned "
                    + response.statusCode() + ": " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Syntology request failed: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Syntology request interrupted: " + path, e);
        }
    }

    private static String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalStateException("Syntology response missing " + field);
        }
        return value.asText();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
