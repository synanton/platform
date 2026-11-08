package org.synanton.synflux.extraction.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** HTTP adapter for Relix's isolated extracted-mention write endpoints. */
public final class HttpRelixGraphWriteClient implements GraphWriteClient {

    private final HttpClient client;
    private final String baseUrl;
    private final ObjectMapper mapper;
    private final GraphWriteTokenMinter tokenMinter;

    public HttpRelixGraphWriteClient(String baseUrl, ObjectMapper mapper, GraphWriteTokenMinter tokenMinter) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
        this.tokenMinter = tokenMinter;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public int upsertMentions(List<GraphMention> mentions) {
        return post("/graph/mentions", Map.of("mentions", mentions));
    }

    @Override
    public int upsertRelations(List<GraphRelation> relations) {
        return post("/graph/relations", Map.of("relations", relations));
    }

    private int post(String path, Map<String, ?> payload) {
        try {
            String body = mapper.writeValueAsString(payload);
            String tenant = payload.values().stream()
                    .filter(List.class::isInstance)
                    .map(List.class::cast)
                    .filter(list -> !list.isEmpty())
                    .map(list -> list.getFirst())
                    .map(row -> {
                        if (row instanceof GraphMention mention) return mention.tenantId();
                        if (row instanceof GraphRelation relation) return relation.tenantId();
                        return "";
                    })
                    .findFirst().orElse("");
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + tokenMinter.mint(tenant))
                    .header("X-Tenant-ID", tenant)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Relix graph write returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            Map<String, Object> result = mapper.readValue(response.body(), Map.class);
            Object count = result.get("count");
            return count instanceof Number number ? number.intValue() : -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Relix graph write interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Relix graph write failed: " + baseUrl + path, e);
        }
    }
}
