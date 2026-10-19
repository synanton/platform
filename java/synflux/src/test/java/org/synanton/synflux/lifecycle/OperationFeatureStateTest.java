package org.synanton.synflux.lifecycle;

import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.IngestionRunRow;
import org.synanton.ingestioncache.domain.OperationRow;
import org.synanton.ingestioncache.domain.SyncResultRow;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * #294 — the anti-omission rule. A requested feature the extraction plane
 * reported FEATURE_UNSUPPORTED makes the Operation PARTIAL even though no
 * resource failed: reporting FULL by omission is the contract violation.
 */
class OperationFeatureStateTest {

    private final OperationService service = new OperationService(
            mock(IngestionCacheClient.class),
            Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC));

    private OperationRow running() {
        Instant now = Instant.parse("2026-10-19T11:59:00Z");
        return new OperationRow("tenant-a", UUID.randomUUID(), "INGESTION", "RUNNING", "alice",
                now, now, null, 0, 0, null, null, "req-1", null);
    }

    private IngestionRunRow completed() {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new IngestionRunRow("tenant-a", UUID.randomUUID(), UUID.randomUUID(), "src-1",
                "COMPLETED", now, now, new SyncResultRow(0, 1, 0, 0, 0, 0, 0, 0), null);
    }

    @Test
    void unsupportedRequestedFeatureForcesPartial() {
        OperationRow completed = service.completeFromRun(running(), completed(), Map.of(
                "feature_states", Map.of("text", "FEATURE_APPLIED", "ocr", "FEATURE_UNSUPPORTED"),
                "payload_descriptor", Map.of("processor_id", "pdf-adapter")));

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.outcome()).isEqualTo("PARTIAL");
        assertThat(completed.detailsJson()).contains("FEATURE_UNSUPPORTED").contains("pdf-adapter");
    }

    @Test
    void appliedFeaturesKeepFull() {
        OperationRow completed = service.completeFromRun(running(), completed(), Map.of(
                "feature_states", Map.of("text", "FEATURE_APPLIED")));

        assertThat(completed.outcome()).isEqualTo("FULL");
        assertThat(completed.detailsJson()).contains("FEATURE_APPLIED");
    }

    @Test
    void noDetailsKeepsFull() {
        assertThat(service.completeFromRun(running(), completed()).outcome()).isEqualTo("FULL");
    }
}
