package org.synanton.synflux.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ExtractionMetricsTest {

    @Test
    void recordsBoundedLabelsAndAcceptanceGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExtractionMetrics metrics = new ExtractionMetrics(registry);

        metrics.recordExtraction("eval-llm", "llm", "COMPLETED", 1200, 8, 3, 2, 1);

        assertThat(registry.get("extraction_completed_total")
                .tags("provider", "llm", "tenant_class", "eval", "outcome", "COMPLETED")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("extraction_duration_seconds")
                .tag("provider", "llm").timer().count()).isEqualTo(1);
        assertThat(registry.get("extraction_entities_count")
                .tag("provider", "llm").summary().count()).isEqualTo(1);
        assertThat(registry.get("extraction_relations_candidates_count")
                .tag("provider", "llm").summary().count()).isEqualTo(1);
        assertThat(registry.get("relation_acceptance_ratio")
                .tag("provider", "llm").gauge().value()).isCloseTo(2.0 / 3.0,
                        org.assertj.core.data.Offset.offset(0.001));
        assertThat(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream().map(tag -> tag.getKey()))
                .toList())
                .as("tenant/version/document identity must stay out of metric labels")
                .doesNotContain("tenant_id", "source_version_id", "document_id");
    }

    @Test
    void lifecycleAndAttributionCountersAreBounded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExtractionMetrics metrics = new ExtractionMetrics(registry);

        metrics.lifecycleRegression("activation", "llm");
        metrics.attributionError("records_subject", "llm");

        assertThat(registry.get("lifecycle_regressions_total")
                .tags("type", "activation", "provider", "llm").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("attribution_errors_total")
                .tags("error_type", "records_subject", "provider", "llm").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void tenantClassIsBoundedToThreeClasses() {
        assertThat(ExtractionMetrics.tenantClass("demo")).isEqualTo("demo");
        assertThat(ExtractionMetrics.tenantClass("eval-llm")).isEqualTo("eval");
        assertThat(ExtractionMetrics.tenantClass("isolated-test")).isEqualTo("other");
    }
}
