package org.synanton.ingestioncache.claim;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synanton.common.resolution.FailureClass;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ResolutionWorkRow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class ClaimServiceTest {

    private final IngestionCacheClient client = mock(IngestionCacheClient.class);
    private final ClaimService service = new ClaimService(client);

    @Test
    void retryableFailureReturnsToPendingWithinRetryBudget() {
        ResolutionWorkRow row = work(0);
        Instant now = Instant.now();

        service.releaseResolution(row, "worker", FailureClass.INFRASTRUCTURE_RETRYABLE,
                "neo4j down", 5, 30, now);

        verify(client).releaseResolutionWork(eq(row), eq("worker"), eq("PENDING"),
                eq("INFRASTRUCTURE_RETRYABLE"), anyString(), any());
    }

    @Test
    void exhaustedRetryBudgetBecomesTerminalFailed() {
        ResolutionWorkRow row = work(4);
        Instant now = Instant.now();

        service.releaseResolution(row, "worker", FailureClass.INFRASTRUCTURE_RETRYABLE,
                "neo4j down", 5, 30, now);

        verify(client).releaseResolutionWork(eq(row), eq("worker"), eq("FAILED"),
                eq("INFRASTRUCTURE_RETRYABLE"), anyString(), eq(null));
    }

    @Test
    void contractViolationIsTerminalImmediately() {
        ResolutionWorkRow row = work(0);
        Instant now = Instant.now();

        service.releaseResolution(row, "worker", FailureClass.CONTRACT_VIOLATION,
                "bad row", 5, 30, now);

        verify(client).releaseResolutionWork(eq(row), eq("worker"), eq("FAILED"),
                eq("CONTRACT_VIOLATION"), anyString(), eq(null));
    }

    @Test
    void versionStatusIsPartialWhileReviewRequiredWorkRemains() {
        ResolutionWorkRow row = work(0);
        ResolutionWorkRow completed = new ResolutionWorkRow(
                row.tenantId(), row.sourceVersionId(), "mention-2", row.connectorId(),
                row.sourceId(), row.resourceId(), row.contentRefId(), 0, 0, 3, 0, 3,
                "Acme", "Organization", "COMPLETED", null, null, 1, null, Instant.now(),
                Instant.now(), null, null, "REVIEW_REQUIRED");
        when(client.listResolutionWorkRows(row.tenantId(), row.sourceVersionId()))
                .thenReturn(List.of(completed));

        service.refreshVersionStatus(row, Instant.now());

        verify(client).markResolutionStatus(eq(row.tenantId()), eq(row.connectorId()),
                eq(row.sourceId()), eq(row.resourceId()), eq(row.sourceVersionId()),
                eq("PARTIAL"), any(), any(), anyMap());
    }

    @Test
    void completedFailedAndBackedOffRowsAreNotClaimable() {
        Instant now = Instant.now();
        ResolutionWorkRow completed = new ResolutionWorkRow(
                "tenant-a", UUID.randomUUID(), "m", "c", "s", "r", UUID.randomUUID(),
                0, 0, 1, 0, 1, "x", "T", "COMPLETED", null, null, 1, null, now, now,
                null, null, "RESOLVED");
        ResolutionWorkRow failed = new ResolutionWorkRow(
                "tenant-a", UUID.randomUUID(), "m", "c", "s", "r", UUID.randomUUID(),
                0, 0, 1, 0, 1, "x", "T", "FAILED", null, null, 5, null, now, now,
                "error", "CONTRACT_VIOLATION", null);
        ResolutionWorkRow backedOff = new ResolutionWorkRow(
                "tenant-a", UUID.randomUUID(), "m", "c", "s", "r", UUID.randomUUID(),
                0, 0, 1, 0, 1, "x", "T", "PENDING", null, null, 1,
                now.plusSeconds(30), now, now, null, null, null);

        assertThat(completed.claimable(now)).isFalse();
        assertThat(failed.claimable(now)).isFalse();
        assertThat(backedOff.claimable(now)).isFalse();
    }

    private static ResolutionWorkRow work(int attempts) {
        return new ResolutionWorkRow(
                "tenant-a", UUID.randomUUID(), "mention-1", "connector", "source", "resource",
                UUID.randomUUID(), 0, 0, 3, 0, 3, "Acme", "Organization",
                "PENDING", null, null, attempts, null, null, Instant.now(), null, null, null);
    }
}
