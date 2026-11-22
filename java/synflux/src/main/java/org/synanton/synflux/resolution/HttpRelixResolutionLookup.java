package org.synanton.synflux.resolution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.synanton.common.jwt.ServiceTokenMinter;
import org.synanton.common.resolution.ActiveResolution;
import org.synanton.common.resolution.ExtractedMentionGraphReadPort;
import org.synanton.common.resolution.ExtractedRelationView;
import org.synanton.common.resolution.ResolutionGraphLookup;
import org.synanton.common.resolution.RetryableProcessingException;

/** HTTP read client for Relix's authoritative resolution/extracted-relation lookups. */
public final class HttpRelixResolutionLookup implements ResolutionGraphLookup,
        ExtractedMentionGraphReadPort {

    private static final TypeReference<List<ActiveResolution>> ACTIVE =
            new TypeReference<>() {};
    private static final TypeReference<List<ExtractedRelationView>> EXTRACTED =
            new TypeReference<>() {};

    private final HttpClient client;
    private final String baseUrl;
    private final ServiceTokenMinter tokenMinter;
    private final ObjectMapper mapper;

    public HttpRelixResolutionLookup(String baseUrl, ServiceTokenMinter tokenMinter, ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.tokenMinter = tokenMinter;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public List<ActiveResolution> getActiveResolutions(String tenantId, List<String> mentionIds) {
        try {
            String body = mapper.writeValueAsString(Map.of("mention_ids",
                    mentionIds == null ? List.of() : mentionIds));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/resolutions/active"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token(tenantId))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new RetryableProcessingException("Relix active-resolution lookup returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            return mapper.readValue(response.body(), ACTIVE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableProcessingException("Active-resolution lookup interrupted", e);
        } catch (RetryableProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableProcessingException("Active-resolution lookup failed", e);
        }
    }

    @Override
    public Optional<ExtractedRelationView> getExtractedRelation(String tenantId, String relationId) {
        if (relationId == null || relationId.isBlank()) {
            return Optional.empty();
        }
        try {
            String encoded = URLEncoder.encode(relationId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/v1/extracted-relations/" + encoded))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token(tenantId))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() / 100 != 2) {
                throw new RetryableProcessingException("Relix extracted-relation lookup returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            return Optional.of(mapper.readValue(response.body(), ExtractedRelationView.class));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableProcessingException("Extracted-relation lookup interrupted", e);
        } catch (RetryableProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableProcessingException("Extracted-relation lookup failed", e);
        }
    }

    @Override
    public List<ExtractedRelationView> listExtractedRelations(String tenantId, String sourceVersionId) {
        try {
            String query = sourceVersionId == null || sourceVersionId.isBlank()
                    ? "" : "?sourceVersionId=" + URLEncoder.encode(sourceVersionId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/v1/extracted-relations" + query))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token(tenantId))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new RetryableProcessingException("Relix extracted-relation list returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            return mapper.readValue(response.body(), EXTRACTED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableProcessingException("Extracted-relation list interrupted", e);
        } catch (RetryableProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableProcessingException("Extracted-relation list failed", e);
        }
    }

    private String token(String tenantId) {
        return tokenMinter.mint(tenantId, "synflux-relation-validator");
    }
}
