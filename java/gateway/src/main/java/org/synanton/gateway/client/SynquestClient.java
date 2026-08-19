package org.synanton.gateway.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.synanton.gateway.domain.Hit;

import java.io.Serial;
import java.time.Duration;
import java.util.List;

public class SynquestClient {

    private static final Logger log = LoggerFactory.getLogger(SynquestClient.class);

    private final WebClient webClient;
    private final long timeoutMs;

    public SynquestClient(WebClient webClient, long timeoutMs) {
        this.webClient = webClient;
        this.timeoutMs = timeoutMs;
    }

    public SynquestResponse search(String tenant, String query, int topK) {
        SynquestRequest req = new SynquestRequest(tenant, query, topK);
        try {
            SynquestResponse resp = webClient.post()
                    .uri("/search")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(SynquestResponse.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
            return resp != null ? resp : new SynquestResponse(List.of(), 0);
        } catch (Exception e) {
            log.warn("synquest search failed: {}", e.getMessage());
            throw new SynquestUnavailableException("synquest unavailable: " + e.getMessage(), e);
        }
    }

    public record SynquestRequest(String tenant, String query, int topK) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SynquestResponse(List<Hit> hits, int total) {}

    public static class SynquestUnavailableException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
        public SynquestUnavailableException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
