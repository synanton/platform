package org.synanton.relix.syntology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.synanton.common.jwt.ServiceTokenMinter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** HTTP client for Syntology's active-ontology-version bootstrap lookup. */
public final class HttpSyntologyActiveVersionClient implements SyntologyActiveVersionClient {

    private final HttpClient client;
    private final String baseUrl;
    private final ServiceTokenMinter tokenMinter;
    private final ObjectMapper mapper;

    public HttpSyntologyActiveVersionClient(String baseUrl, ServiceTokenMinter tokenMinter,
                                            ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.tokenMinter = tokenMinter;
        this.mapper = mapper.registerModule(new JavaTimeModule());
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public ActiveOntologyVersion getActiveVersion(String tenantId) {
        try {
            String encoded = URLEncoder.encode(tenantId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/v1/tenants/" + encoded + "/active-ontology-version"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer "
                            + tokenMinter.mint(tenantId, "relix-bootstrap"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return switch (response.statusCode()) {
                case 200 -> mapper.readValue(response.body(), ActiveOntologyVersion.class);
                case 401, 403 -> throw new SyntologyAuthException(
                        "Syntology active-version lookup rejected service identity: HTTP "
                                + response.statusCode());
                case 404 -> throw new OntologyVersionUnavailableException(
                        "No active ontology for tenant " + tenantId);
                default -> throw new OntologyLookupFailedException(
                        "Syntology active-version lookup returned HTTP " + response.statusCode(),
                        null);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OntologyLookupFailedException("Syntology active-version lookup interrupted", e);
        } catch (SyntologyAuthException | OntologyVersionUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new OntologyLookupFailedException("Syntology active-version lookup failed", e);
        }
    }
}
