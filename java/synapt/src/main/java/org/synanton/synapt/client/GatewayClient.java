package org.synanton.synapt.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.synanton.synapt.domain.Hints;
import org.synanton.synapt.domain.SearchRequest;

import java.time.Duration;

public class GatewayClient {

    private static final Logger log = LoggerFactory.getLogger(GatewayClient.class);

    private final WebClient webClient;
    private final long timeoutMs;

    public GatewayClient(WebClient webClient, long timeoutMs) {
        this.webClient = webClient;
        this.timeoutMs = timeoutMs;
    }

    public Object query(SearchRequest request, String tenant) {
        GatewayQueryRequest gqr = new GatewayQueryRequest(
                tenant,
                request.query(),
                request.effectiveTopK(),
                request.hints()
        );
        try {
            return webClient.post()
                    .uri("/query")
                    .bodyValue(gqr)
                    .retrieve()
                    .bodyToMono(Object.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("Gateway returned {}: {}", e.getStatusCode(), e.getMessage());
            throw new GatewayException(e.getStatusCode().value(), e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Gateway call failed: {}", e.getMessage());
            throw new GatewayException(502, "Gateway unreachable: " + e.getMessage(), e);
        }
    }

    private record GatewayQueryRequest(String tenant, String query, int topK, Hints hints) {}

    public static class GatewayException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final int statusCode;

        public GatewayException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
