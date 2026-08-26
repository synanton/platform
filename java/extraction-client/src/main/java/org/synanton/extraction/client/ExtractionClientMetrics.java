package org.synanton.extraction.client;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Platform-side extraction client metrics (SCEP-5).
 */
public class ExtractionClientMetrics {

    private final MeterRegistry meterRegistry;

    public ExtractionClientMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRequest(String mode, String outcome) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("extraction_client_requests_total", "mode", mode, "outcome", outcome).increment();
    }

    public void recordFallback(String reason) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("extraction_client_fallback_total", "reason", reason).increment();
    }

    public Timer.Sample startLatencySample() {
        if (meterRegistry == null) {
            return null;
        }
        return Timer.start(meterRegistry);
    }

    public void recordLatency(Timer.Sample sample, String mode, String outcome) {
        if (meterRegistry == null || sample == null) {
            return;
        }
        sample.stop(meterRegistry.timer("extraction_client_latency", "mode", mode, "outcome", outcome));
    }

    public void recordLatencyMillis(long durationMs, String mode, String outcome) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.timer("extraction_client_latency", "mode", mode, "outcome", outcome)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }
}
