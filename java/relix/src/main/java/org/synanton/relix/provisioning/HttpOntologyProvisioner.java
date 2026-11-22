package org.synanton.relix.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Syntology active-version and create-version HTTP surface. */
public final class HttpOntologyProvisioner implements OntologyProvisioner {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final String baseUrl;
    private final String token;

    public HttpOntologyProvisioner(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public Optional<String> activeVersion(String tenantId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/tenants/"
                            + URLEncoder.encode(tenantId, StandardCharsets.UTF_8)
                            + "/active-ontology-version"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Syntology active version returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            JsonNode node = MAPPER.readTree(response.body());
            String version = node.path("activeVersion").asText(null);
            return version == null || version.isBlank() ? Optional.empty() : Optional.of(version);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Syntology lookup interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Syntology active version lookup failed: " + e, e);
        }
    }

    @Override
    public void createVersion(String tenantId, String version, byte[] ttl) {
        try {
            String boundary = "----synanton-provision-" + UUID.randomUUID();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"ontology.ttl\"\r\n"
                    + "Content-Type: text/turtle\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(ttl == null ? new byte[0] : ttl);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl
                            + "/api/v1/ontology/versions?version="
                            + URLEncoder.encode(version, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Syntology create version returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Syntology create version interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Syntology create version failed: " + e, e);
        }
    }
}
