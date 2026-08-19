package org.synanton.gateway.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.Serial;
import java.time.Duration;
import java.util.Map;

public class PlannerClient {

    private static final Logger log = LoggerFactory.getLogger(PlannerClient.class);

    private final WebClient webClient;
    private final long timeoutMs;
    private final boolean retryOnce5xx;

    public PlannerClient(WebClient webClient, long timeoutMs, boolean retryOnce5xx) {
        this.webClient = webClient;
        this.timeoutMs = timeoutMs;
        this.retryOnce5xx = retryOnce5xx;
    }

    public PlannerResponse plan(String query, String tenant) {
        PlannerRequest req = new PlannerRequest(query, tenant);
        try {
            return callPlanner(req);
        } catch (WebClientResponseException e) {
            if (retryOnce5xx && e.getStatusCode().is5xxServerError()) {
                log.warn("Planner returned 5xx ({}), retrying once", e.getStatusCode());
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return callPlanner(req);
            }
            throw new PlannerUnavailableException("Planner error: " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new PlannerUnavailableException("Planner call failed: " + e.getMessage(), e);
        }
    }

    private PlannerResponse callPlanner(PlannerRequest req) {
        return webClient.post()
                .uri("/plan")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(PlannerResponse.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .block();
    }

    public record PlannerRequest(String query, String tenant) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlannerResponse(
            String templateId,
            String query,
            String tenant,
            Map<String, Object> slots,
            double confidence
    ) {}

    public static class PlannerUnavailableException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
        public PlannerUnavailableException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
