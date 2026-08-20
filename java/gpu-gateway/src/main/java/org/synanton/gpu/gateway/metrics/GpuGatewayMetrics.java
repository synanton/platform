package org.synanton.gpu.gateway.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class GpuGatewayMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger idempotencyStoreHealthy = new AtomicInteger(1);

    public GpuGatewayMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("gpu_idempotency_store_healthy", idempotencyStoreHealthy);
    }

    public void recordExecution(String model, String modelVersion, String outcome, long durationMs) {
        meterRegistry.counter("gpu_execute_total",
                "model", model, "model_version", modelVersion, "outcome", outcome).increment();
        meterRegistry.timer("gpu_execute_duration_seconds",
                "model", model, "model_version", modelVersion)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordIdempotencyHit(String model) {
        meterRegistry.counter("gpu_idempotency_hit_total", "model", model).increment();
    }

    public void recordAdmissionRejected(String model, String reason) {
        meterRegistry.counter("gpu_admission_rejected_total", "model", model, "reason", reason).increment();
    }

    public void recordModelNotReady(String model) {
        meterRegistry.counter("gpu_model_not_ready_total", "model", model).increment();
    }

    public void setIdempotencyStoreHealthy(boolean healthy) {
        idempotencyStoreHealthy.set(healthy ? 1 : 0);
    }

    public void recordCancellation(String model, String outcome) {
        meterRegistry.counter("gpu_cancel_total", "model", model, "outcome", outcome).increment();
    }
}
