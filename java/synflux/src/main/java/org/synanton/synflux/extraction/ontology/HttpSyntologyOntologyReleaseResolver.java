package org.synanton.synflux.extraction.ontology;

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

/** Resolves the active Syntology release and its digest for extraction provenance. */
public final class HttpSyntologyOntologyReleaseResolver implements OntologyReleaseResolver {

    private final HttpClient client;
    private final String baseUrl;
    private final ObjectMapper mapper;
    private final ServiceTokenMinter tokenMinter;

    public HttpSyntologyOntologyReleaseResolver(String baseUrl, ObjectMapper mapper) {
        this(baseUrl, mapper, null);
    }

    public HttpSyntologyOntologyReleaseResolver(String baseUrl, ObjectMapper mapper,
                                                ServiceTokenMinter tokenMinter) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
        this.tokenMinter = tokenMinter;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public Optional<OntologyRelease> resolve(String tenantId) {
        try {
            String path = "/v1/tenants/" + URLEncoder.encode(tenantId, StandardCharsets.UTF_8)
                    + "/active-ontology-version";
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-Tenant-ID", tenantId)
                    .GET();
            if (tokenMinter != null) {
                builder.header("Authorization", "Bearer " + tokenMinter.mint(tenantId, "synflux-extraction"));
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Syntology active release returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            Map<?, ?> body = mapper.readValue(response.body(), Map.class);
            String releaseId = string(body.get("releaseId"));
            if (releaseId == null || releaseId.isBlank()) {
                releaseId = string(body.get("versionRef"));
            }
            if (releaseId == null || releaseId.isBlank()) {
                throw new IllegalStateException("Syntology active release has no release identity");
            }
            return Optional.of(new OntologyRelease(releaseId, string(body.get("releaseDigest"))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Syntology active release lookup interrupted", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Syntology active release lookup failed: " + baseUrl, e);
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
