package org.synanton.gateway.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.synanton.gateway.domain.GraphEdge;
import org.synanton.gateway.domain.GraphEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class RelixClient {

    private static final Logger log = LoggerFactory.getLogger(RelixClient.class);

    private final WebClient webClient;
    private final long timeoutMs;

    public RelixClient(WebClient webClient, long timeoutMs) {
        this.webClient = webClient;
        this.timeoutMs = timeoutMs;
    }

    public RelixResponse graphQuery(String tenant, String query, Map<String, Object> slots) {
        RelixRequest req = new RelixRequest(tenant, query, slots);
        try {
            RelixResponse resp = webClient.post()
                    .uri("/graph/query")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(RelixResponse.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
            return resp != null ? resp : RelixResponse.empty();
        } catch (Exception e) {
            log.warn("relix graph query failed: {}", e.getMessage());
            throw new RelixUnavailableException("relix unavailable: " + e.getMessage(), e);
        }
    }

    public record RelixRequest(String tenant, String query, Map<String, Object> slots) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RelixResponse(
            List<GraphEntity> entities,
            List<GraphEdge> edges,
            List<Object> paths
    ) {
        public static RelixResponse empty() {
            return new RelixResponse(List.of(), List.of(), List.of());
        }
    }

    public static class RelixUnavailableException extends RuntimeException {
        public RelixUnavailableException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
