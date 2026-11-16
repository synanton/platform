package org.synanton.relix.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Relix projection-provisioning HTTP surface. */
public final class HttpGraphProvisioner implements GraphProvisioner {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final String baseUrl;
    private final String token;

    public HttpGraphProvisioner(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public GraphProvisionOutcome provision(String tenantId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/admin/tenants/"
                            + URLEncoder.encode(tenantId, StandardCharsets.UTF_8) + "/provision"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return GraphProvisionOutcome.FAILED;
            }
            JsonNode node = MAPPER.readTree(response.body());
            return switch (node.path("outcome").asText("")) {
                case "NEWLY_PROVISIONED" -> GraphProvisionOutcome.CREATED;
                case "ALREADY_INITIALIZED" -> GraphProvisionOutcome.ALREADY_PRESENT;
                default -> GraphProvisionOutcome.FAILED;
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GraphProvisionOutcome.FAILED;
        } catch (Exception e) {
            return GraphProvisionOutcome.FAILED;
        }
    }
}
