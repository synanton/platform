package org.synanton.gpu.client;

import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.ExecutionResponse;
import org.synanton.gpu.v1.ExecutionState;
import org.synanton.gpu.v1.GPUExecutionServiceGrpc;

import java.util.concurrent.TimeUnit;

/**
 * Shared fail-closed Execute loop for the GPU-plane clients (EMBED, RERANK). It returns only a
 * SUCCESS response and throws {@link GpuPlaneException} with the canonical code otherwise.
 *
 * <ul>
 *   <li>Only transient outcomes are retried: capacity denials, transport UNAVAILABLE, a
 *       retryable MODEL_NOT_READY, and a provider 429 after {@code rate-limited-backoff-ms}.</li>
 *   <li>Every attempt, retries included, is paced by {@code max-requests-per-minute}.</li>
 *   <li>Payloads (inputs, queries, passages) are never logged.</li>
 * </ul>
 */
final class GpuPlaneExecutor {

    private static final Logger log = LoggerFactory.getLogger(GpuPlaneExecutor.class);

    private final GpuPlaneClientProperties props;
    private final GPUExecutionServiceGrpc.GPUExecutionServiceBlockingStub stub;
    private final RequestPacer pacer;

    GpuPlaneExecutor(GpuPlaneClientProperties props, GPUExecutionServiceGrpc.GPUExecutionServiceBlockingStub stub) {
        this.props = props;
        this.stub = stub;
        this.pacer = new RequestPacer(props.getMaxRequestsPerMinute());
    }

    record Outcome(ExecutionResponse response, long latencyMs) {}

    Outcome execute(ExecutionRequest exec) {
        String op = exec.getOperation().name();
        int maxAttempts = Math.max(1, props.getRetry().getMaxAttempts());
        for (int attempt = 1; ; attempt++) {
            pacer.acquire();
            long start = System.currentTimeMillis();
            ExecutionResponse response;
            try {
                response = stub.withDeadlineAfter(props.getTimeoutMs(), TimeUnit.MILLISECONDS).execute(exec);
            } catch (StatusRuntimeException e) {
                String code = GpuErrorCodes.canonicalCode(e);
                if (GpuErrorCodes.isTransient(e) && attempt < maxAttempts) {
                    retryLog(op, exec, code, attempt, maxAttempts);
                    sleep(backoff(attempt));
                    continue;
                }
                log.warn("GPU plane {} denied: code={} grpc={} request={} tenant={} model={}",
                        op, code, e.getStatus().getCode(), exec.getRequestId(), exec.getTenantId(), exec.getModel());
                throw new GpuPlaneException(code, "GPU plane " + op + " failed (" + e.getStatus().getCode() + ")", e);
            }
            long latencyMs = System.currentTimeMillis() - start;
            if (response.getState() == ExecutionState.SUCCESS) {
                return new Outcome(response, latencyMs);
            }
            String code = GpuErrorCodes.canonicalCode(response);
            if (code == null) {
                code = "state_" + response.getState().name().toLowerCase();
            }
            if (GpuErrorCodes.isTransient(response) && attempt < maxAttempts) {
                retryLog(op, exec, code, attempt, maxAttempts);
                long wait = backoff(attempt);
                if (GpuErrorCodes.PROVIDER_RATE_LIMITED.equals(code)) {
                    wait = Math.max(wait, (long) props.getRetry().getRateLimitedBackoffMs() * attempt);
                }
                sleep(wait);
                continue;
            }
            log.warn("GPU plane {} failed: state={} code={} reason={} upstream_request_id={} request={} tenant={} model={}",
                    op, response.getState(), code, response.getError().getReason(), response.getUpstreamRequestId(),
                    exec.getRequestId(), exec.getTenantId(), exec.getModel());
            throw new GpuPlaneException(code, "GPU plane " + op + " " + response.getState());
        }
    }

    private void retryLog(String op, ExecutionRequest exec, String code, int attempt, int max) {
        log.info("GPU plane {} transient code={} request={} — retry {}/{}", op, code, exec.getRequestId(), attempt, max - 1);
    }

    private long backoff(int attempt) {
        return (long) (props.getRetry().getBackoffBaseMs() * Math.pow(2, attempt - 1));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new GpuPlaneException("cancelled", "interrupted while backing off");
        }
    }
}
