package org.synanton.synflux.extraction.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.common.jwt.ServiceTokenMinter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** HTTP adapter for Syntology's relation validation endpoint. */
public final class HttpSyntologyRelationValidator implements RelationValidator {

    private final HttpClient client;
    private final String baseUrl;
    private final ObjectMapper mapper;
    private final ServiceTokenMinter tokenMinter;

    public HttpSyntologyRelationValidator(String baseUrl, ObjectMapper mapper) {
        this(baseUrl, mapper, null);
    }

    public HttpSyntologyRelationValidator(String baseUrl, ObjectMapper mapper, ServiceTokenMinter tokenMinter) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
        this.tokenMinter = tokenMinter;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public RelationValidation validate(String tenantId, String subjectType, String predicate,
                                       String objectType, String ontologyVersion) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "subjectType", subjectType,
                    "predicate", predicate,
                    "objectType", objectType,
                    "ontologyVersion", ontologyVersion));
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/api/v1/ontology/validate-relation"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("X-Tenant-ID", tenantId);
            if (tokenMinter != null) {
                builder.header("Authorization", "Bearer " + tokenMinter.mint(tenantId, "synflux-graph"));
            }
            HttpRequest request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Syntology relation validation returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            Map<String, Object> parsed = mapper.readValue(response.body(), Map.class);
            boolean conformant = Boolean.TRUE.equals(parsed.get("conformant"));
            List<RelationValidation.Violation> violations = new ArrayList<>();
            Object raw = parsed.get("violations");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        violations.add(new RelationValidation.Violation(
                                stringOrNull(map.get("code")),
                                stringOrNull(map.get("message")),
                                stringOrNull(map.get("path"))));
                    }
                }
            }
            return new RelationValidation(conformant, violations);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Syntology validation interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Syntology relation validation failed: " + baseUrl, e);
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
