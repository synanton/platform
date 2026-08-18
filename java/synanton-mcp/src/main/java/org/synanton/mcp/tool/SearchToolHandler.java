package org.synanton.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.synanton.mcp.app.McpProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class SearchToolHandler implements ToolHandler {
    private static final Logger log = LoggerFactory.getLogger(SearchToolHandler.class);
    private final String synaptUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final long timeoutMs;

    public SearchToolHandler(McpProperties props) {
        this.synaptUrl = props.synaptUrl();
        this.timeoutMs = props.toolTimeoutMs();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.objectMapper = new ObjectMapper();
    }

    @Override public String toolName() { return "search"; }
    @Override public String description() {
        return "Search the tenant's knowledge corpus and return ranked hits with an optional synthesised answer.";
    }
    @Override public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string", "description", "The search query."),
                "top_k", Map.of("type", "integer", "default", 10, "description", "Maximum number of hits to return.")
            ),
            "required", java.util.List.of("query")
        );
    }

    @Override
    public McpContent invoke(Map<String, Object> arguments, String tenantId, String authHeader) {
        String query = (String) arguments.getOrDefault("query", "");
        int topK = ((Number) arguments.getOrDefault("top_k", 10)).intValue();

        try {
            String body = objectMapper.writeValueAsString(Map.of("query", query, "top_k", topK));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(synaptUrl + "/search"))
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader)
                .header("X-Tenant-Id", tenantId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofMillis(timeoutMs))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new McpContent("Error: search service returned " + response.statusCode());
            }
            JsonNode json = objectMapper.readTree(response.body());
            return new McpContent(formatSearchResult(query, json));
        } catch (Exception e) {
            log.warn("Search tool failed for query '{}': {}", query, e.getMessage());
            return new McpContent("Error: search service unavailable - " + e.getMessage());
        }
    }

    private String formatSearchResult(String query, JsonNode json) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Search Results for '").append(query).append("'**\n\n");
        JsonNode hits = json.path("hits");
        if (hits.isArray()) {
            int i = 1;
            for (JsonNode hit : hits) {
                sb.append(i++).append(". [").append(hit.path("contentRef").asText("unknown")).append("]");
                sb.append(" Score: ").append(String.format("%.2f", hit.path("score").asDouble(0)));
                sb.append("\n   *").append(hit.path("excerpt").asText("")).append("*\n");
            }
        }
        String answer = json.path("answer").asText("");
        if (!answer.isEmpty()) sb.append("\n**Answer:** ").append(answer);
        return sb.toString();
    }
}
