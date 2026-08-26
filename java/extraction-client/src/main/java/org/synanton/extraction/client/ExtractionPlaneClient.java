package org.synanton.extraction.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import synanton.extraction.v1.ExtractionOperation;
import synanton.extraction.v1.ExtractionResult;
import synanton.extraction.v1.ExtractionServiceGrpc;
import synanton.extraction.v1.ExtractionStatus;
import synanton.extraction.v1.GetOperationsRequest;
import synanton.extraction.v1.GetResultRequest;
import synanton.extraction.v1.PriorityClass;
import synanton.extraction.v1.SubmitExtractionRequest;

import java.util.concurrent.TimeUnit;

/**
 * gRPC client for the Structured Content Extraction Plane.
 *
 * <p>Supports synchronous {@link #extractSync} and asynchronous submit/poll with
 * reconcile-after-timeout semantics: a timed-out submit is resolved by polling
 * {@code GetOperations}, never by blind resubmission with a fresh idempotency key.
 */
public class ExtractionPlaneClient {

    private static final Logger log = LoggerFactory.getLogger(ExtractionPlaneClient.class);

    private final ExtractionClientProperties properties;
    private final ExtractionClientMetrics metrics;
    private volatile ManagedChannel channel;
    private volatile ExtractionServiceGrpc.ExtractionServiceBlockingStub blockingStub;

    public ExtractionPlaneClient(ExtractionClientProperties properties, ExtractionClientMetrics metrics) {
        this(properties, metrics, null);
    }

    ExtractionPlaneClient(
            ExtractionClientProperties properties,
            ExtractionClientMetrics metrics,
            ManagedChannel testChannel) {
        this.properties = properties;
        this.metrics = metrics;
        if (testChannel != null) {
            this.channel = testChannel;
            this.blockingStub = ExtractionServiceGrpc.newBlockingStub(testChannel);
        }
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    /**
     * Primary entry point: uses sync or async mode from configuration.
     */
    public ExtractionResult extract(SubmitExtractionRequest request) {
        if (!isEnabled()) {
            throw new IllegalStateException("Extraction plane client is disabled");
        }
        if (properties.isAsyncMode()) {
            return extractAsyncWithReconcile(request);
        }
        return extractSync(request);
    }

    public ExtractionResult extractSync(SubmitExtractionRequest request) {
        ensureChannel();
        TimerHolder timer = TimerHolder.start(metrics, "sync");
        try {
            ExtractionResult result = blockingStub
                    .withDeadlineAfter(properties.deadlineSeconds(), TimeUnit.SECONDS)
                    .extractSync(request);
            metrics.recordRequest("sync", outcomeFor(result));
            timer.success(outcomeFor(result));
            return result;
        } catch (StatusRuntimeException e) {
            metrics.recordRequest("sync", "error");
            timer.error();
            throw e;
        }
    }

    /**
     * Submits work and polls until terminal state. On submit deadline exceeded, reconciles via
     * {@link #getOperations} using the same idempotency key (safe resubmit) then polls.
     */
    public ExtractionResult extractAsyncWithReconcile(SubmitExtractionRequest request) {
        ensureChannel();
        TimerHolder timer = TimerHolder.start(metrics, "async");
        try {
            ExtractionOperation operation = submitExtraction(request);
            ExtractionOperation terminal = pollUntilTerminal(request.getTenantId(), operation.getOperationId());
            if (!isTerminalSuccess(terminal.getStatus())) {
                metrics.recordRequest("async", "failed");
                timer.error();
                return buildFailedResult(terminal);
            }
            ExtractionResult result = getResult(
                    request.getTenantId(), terminal.getOperationId(), itemIndexFor(terminal));
            metrics.recordRequest("async", outcomeFor(result));
            timer.success(outcomeFor(result));
            return result;
        } catch (StatusRuntimeException e) {
            metrics.recordRequest("async", "error");
            timer.error();
            throw e;
        }
    }

    public ExtractionOperation submitExtraction(SubmitExtractionRequest request) {
        ensureChannel();
        try {
            return blockingStub
                    .withDeadlineAfter(properties.deadlineSeconds(), TimeUnit.SECONDS)
                    .submitExtraction(request);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                log.warn("Submit timed out for tenant={}, idempotencyKey={}; reconciling via idempotent resubmit",
                        request.getTenantId(), request.getIdempotencyKey());
                return reconcileSubmitAfterTimeout(request);
            }
            throw e;
        }
    }

    public ExtractionOperation getOperations(String tenantId, String operationId) {
        ensureChannel();
        var response = blockingStub
                .withDeadlineAfter(properties.deadlineSeconds(), TimeUnit.SECONDS)
                .getOperations(GetOperationsRequest.newBuilder()
                        .setTenantId(tenantId)
                        .addOperationIds(operationId)
                        .build());
        if (response.getOperationsCount() == 0) {
            throw new StatusRuntimeException(Status.NOT_FOUND.withDescription(
                    "operation not found: " + operationId));
        }
        return response.getOperations(0);
    }

    public ExtractionResult getResult(String tenantId, String operationId, int itemIndex) {
        ensureChannel();
        return blockingStub
                .withDeadlineAfter(properties.deadlineSeconds(), TimeUnit.SECONDS)
                .getResult(GetResultRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setOperationId(operationId)
                        .setItemIndex(itemIndex)
                        .build());
    }

    public PriorityClass defaultPriorityClass() {
        return parsePriority(properties.defaultPriority());
    }

    private ExtractionOperation reconcileSubmitAfterTimeout(SubmitExtractionRequest request) {
        // Same idempotency key is contract-safe and returns the existing operation handle.
        return blockingStub
                .withDeadlineAfter(properties.deadlineSeconds(), TimeUnit.SECONDS)
                .submitExtraction(request);
    }

    private ExtractionOperation pollUntilTerminal(String tenantId, String operationId) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(Math.max(properties.deadlineSeconds(), 1));
        ExtractionOperation latest = getOperations(tenantId, operationId);
        while (!isTerminal(latest.getStatus())) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription(
                        "timed out polling operation " + operationId));
            }
            sleepPollInterval();
            latest = getOperations(tenantId, operationId);
        }
        return latest;
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(Math.max(properties.pollIntervalSeconds(), 1) * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StatusRuntimeException(Status.CANCELLED.withDescription("poll interrupted"));
        }
    }

    private static boolean isTerminal(ExtractionStatus status) {
        return status == ExtractionStatus.STATUS_COMPLETED
                || status == ExtractionStatus.STATUS_PARTIAL
                || status == ExtractionStatus.STATUS_FAILED
                || status == ExtractionStatus.STATUS_CANCELLED
                || status == ExtractionStatus.STATUS_EXPIRED;
    }

    private static boolean isTerminalSuccess(ExtractionStatus status) {
        return status == ExtractionStatus.STATUS_COMPLETED
                || status == ExtractionStatus.STATUS_PARTIAL;
    }

    private static int itemIndexFor(ExtractionOperation operation) {
        if (operation.getItemsCount() == 0) {
            return 0;
        }
        return operation.getItems(0).getItemIndex();
    }

    private static ExtractionResult buildFailedResult(ExtractionOperation operation) {
        ExtractionResult.Builder builder = ExtractionResult.newBuilder()
                .setOperationId(operation.getOperationId())
                .setStatus(operation.getStatus());
        if (operation.hasError()) {
            builder.setError(operation.getError());
        }
        if (operation.getItemsCount() > 0) {
            var item = operation.getItems(0);
            builder.setItemIndex(item.getItemIndex())
                    .setContentRefId(item.getContentRefId())
                    .setStatus(item.getStatus());
            if (item.hasError()) {
                builder.setError(item.getError());
            }
        }
        return builder.build();
    }

    static String outcomeFor(ExtractionResult result) {
        ExtractionStatus status = result.getStatus();
        if (status == ExtractionStatus.STATUS_COMPLETED) {
            return "completed";
        }
        if (status == ExtractionStatus.STATUS_PARTIAL) {
            return "partial";
        }
        return "failed";
    }

    static PriorityClass parsePriority(String value) {
        if (value == null || value.isBlank()) {
            return PriorityClass.PRIORITY_NORMAL;
        }
        return switch (value.trim().toUpperCase()) {
            case "LOW" -> PriorityClass.PRIORITY_LOW;
            case "HIGH" -> PriorityClass.PRIORITY_HIGH;
            case "CRITICAL" -> PriorityClass.PRIORITY_CRITICAL;
            default -> PriorityClass.PRIORITY_NORMAL;
        };
    }

    private void ensureChannel() {
        if (!isEnabled()) {
            throw new IllegalStateException("Extraction plane client is disabled");
        }
        if (channel == null || channel.isShutdown()) {
            synchronized (this) {
                if (channel == null || channel.isShutdown()) {
                    channel = buildChannel();
                    blockingStub = ExtractionServiceGrpc.newBlockingStub(channel);
                    log.info("Extraction plane client connected to {}", properties.endpoint());
                }
            }
        }
    }

    private ManagedChannel buildChannel() {
        String endpoint = properties.endpoint();
        String[] parts = endpoint.split(":", 2);
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9091;
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }

    private static final class TimerHolder {
        private final ExtractionClientMetrics metrics;
        private final String mode;
        private final io.micrometer.core.instrument.Timer.Sample sample;

        private TimerHolder(ExtractionClientMetrics metrics, String mode,
                            io.micrometer.core.instrument.Timer.Sample sample) {
            this.metrics = metrics;
            this.mode = mode;
            this.sample = sample;
        }

        static TimerHolder start(ExtractionClientMetrics metrics, String mode) {
            return new TimerHolder(metrics, mode, metrics.startLatencySample());
        }

        void success(String outcome) {
            metrics.recordLatency(sample, mode, outcome);
        }

        void error() {
            metrics.recordLatency(sample, mode, "error");
        }
    }
}
