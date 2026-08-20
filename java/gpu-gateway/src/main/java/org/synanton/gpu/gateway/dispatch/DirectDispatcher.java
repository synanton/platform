package org.synanton.gpu.gateway.dispatch;

import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.synanton.gpu.gateway.config.GpuGatewayProperties;
import org.synanton.gpu.v1.CapacityResponse;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.GetCapacityRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

// DirectDispatcher is the default execution strategy.
// It delegates GPU workloads to a vLLM instance via its OpenAI-compatible HTTP API,
// relying on Kubernetes Service load balancing for pod selection.
// No global request scheduling is performed — that is Equalix's responsibility if enabled.
@Component
public class DirectDispatcher implements ExecutionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DirectDispatcher.class);

    private final HttpClient httpClient;
    private final String vllmEndpoint;
    private final Duration timeout;

    public DirectDispatcher(GpuGatewayProperties properties) {
        this.vllmEndpoint = properties.getDispatch().getVllmEndpoint();
        this.timeout = Duration.ofMillis(properties.getDispatch().getTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public DispatchResult dispatch(ExecutionRequest request, String executionId) {
        return switch (request.getOperation()) {
            case SYNTHESIZE -> call(vllmEndpoint + "/v1/chat/completions", request, executionId);
            case EMBED      -> call(vllmEndpoint + "/v1/embeddings", request, executionId);
            case RERANK     -> call(vllmEndpoint + "/v1/rerank", request, executionId);
            default -> DispatchResult.failed("Unsupported operation: " + request.getOperation());
        };
    }

    @Override
    public CapacityResponse capacity(GetCapacityRequest request) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(vllmEndpoint + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
            boolean healthy = response.statusCode() == 200;
            return CapacityResponse.newBuilder()
                    .setModel(request.getModel())
                    .setGpuType(request.getGpuType())
                    .setHealthy(healthy)
                    .setModelLoaded(healthy)
                    .setEstimatedAvailableFraction(healthy ? 1.0 : 0.0)
                    .build();
        } catch (Exception e) {
            log.warn("Capacity check failed for model={}: {}", request.getModel(), e.getMessage());
            return CapacityResponse.newBuilder()
                    .setModel(request.getModel())
                    .setGpuType(request.getGpuType())
                    .setHealthy(false)
                    .build();
        }
    }

    private DispatchResult call(String url, ExecutionRequest request, String executionId) {
        long start = System.currentTimeMillis();
        byte[] payloadBytes = request.getPayload().toByteArray();
        if (payloadBytes.length == 0) {
            return DispatchResult.failed("payload is empty", 0);
        }
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-Execution-Id", executionId)
                    .header("X-Request-Id", request.getRequestId())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payloadBytes))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            long durationMs = System.currentTimeMillis() - start;

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return DispatchResult.ok(response.body(), durationMs, 0, 0);
            }
            log.warn("vLLM returned HTTP {} for execution_id={}", response.statusCode(), executionId);
            return DispatchResult.failed("vLLM HTTP " + response.statusCode(), durationMs);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Dispatch error for execution_id={}: {}", executionId, e.getMessage());
            return DispatchResult.failed(e.getMessage(), durationMs);
        }
    }
}
