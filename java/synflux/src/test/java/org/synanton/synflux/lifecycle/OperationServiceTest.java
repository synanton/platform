package org.synanton.synflux.lifecycle;

import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.IngestionRunRow;
import org.synanton.ingestioncache.domain.OperationRow;
import org.synanton.ingestioncache.domain.SyncResultRow;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationServiceTest {

    @Test
    void sameTenantPrincipalEndpointAndKeyConvergeOnOneOperation() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        UUID operationId = UUID.randomUUID();
        OperationRow accepted = new OperationRow(
            "tenant-a", operationId, "INGESTION", "ACCEPTED", "alice",
            Instant.parse("2026-10-17T12:00:00Z"), null, null, 0, 0, null, null, "req-1", "key-1"
        );
        when(cache.reserveOperationIdempotency(eq("tenant-a"), eq("alice"),
            eq(OperationService.INGESTION_ENDPOINT), eq("key-1"), any(UUID.class), any(Instant.class)))
            .thenReturn(operationId);
        when(cache.readOperation("tenant-a", operationId))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(accepted));
        when(cache.createOperationIfAbsent(any(OperationRow.class))).thenReturn(true);
        OperationService service = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        VerifiedIngestionContext context = new VerifiedIngestionContext("tenant-a", "alice", "USER");

        OperationRow first = service.accept(context, "req-1", "key-1");
        OperationRow retry = service.accept(context, "req-2", "key-1");

        assertThat(first.operationId()).isEqualTo(retry.operationId());
        verify(cache).createOperationIfAbsent(any(OperationRow.class));
    }

    @Test
    void runStatusMapsToPapi3OperationOutcome() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        OperationService service = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        OperationRow running = new OperationRow(
            "tenant-a", UUID.randomUUID(), "INGESTION", "RUNNING", "alice",
            Instant.parse("2026-10-17T11:59:00Z"), Instant.parse("2026-10-17T11:59:01Z"), null,
            0, 2, null, null, "req-1", null
        );
        IngestionRunRow partialRun = new IngestionRunRow(
            "tenant-a", UUID.randomUUID(), running.operationId(), "source-a", "COMPLETED",
            running.startedAt(), Instant.parse("2026-10-17T11:59:02Z"),
            new SyncResultRow(0, 1, 0, 0, 1, 0, 0, 0), null
        );

        OperationRow completed = service.completeFromRun(running, partialRun);

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.outcome()).isEqualTo("PARTIAL");
        assertThat(completed.progressCompleted()).isEqualTo(2);
        verify(cache).upsertOperation(completed);
        verify(cache, never()).reserveOperationIdempotency(any(), any(), any(), any(), any(), any());
    }

    @Test
    void runningOperationTransitionsThroughCancelRequestedAndSignalsWorker() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        OperationService service = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        UUID operationId = UUID.randomUUID();
        OperationRow running = new OperationRow(
            "tenant-a", operationId, "INGESTION", "RUNNING", "alice",
            Instant.parse("2026-10-17T11:59:00Z"), Instant.parse("2026-10-17T11:59:01Z"), null,
            0, 1, null, null, "req-1", null
        );
        when(cache.readOperation("tenant-a", operationId)).thenReturn(Optional.of(running));

        OperationRow cancelRequested = service.cancel("tenant-a", operationId);

        assertThat(cancelRequested.status()).isEqualTo("CANCEL_REQUESTED");
        assertThat(service.cancellationRequested(operationId)).isTrue();
        verify(cache).upsertOperation(cancelRequested);
    }

    @Test
    void failedRunEmbedsItsW2ErrorReferenceInFailedOperation() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        OperationService service = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        UUID operationId = UUID.randomUUID();
        UUID errorId = UUID.randomUUID();
        OperationRow running = new OperationRow(
            "tenant-a", operationId, "INGESTION", "RUNNING", "alice",
            Instant.parse("2026-10-17T11:59:00Z"), Instant.parse("2026-10-17T11:59:01Z"), null,
            0, 1, null, null, "req-1", null
        );
        IngestionRunRow failedRun = new IngestionRunRow(
            "tenant-a", UUID.randomUUID(), operationId, "source-a", "FAILED",
            running.startedAt(), Instant.parse("2026-10-17T11:59:02Z"),
            new SyncResultRow(0, 0, 0, 0, 1, 0, 0, 0), errorId.toString()
        );

        OperationRow failed = service.completeFromRun(running, failedRun);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.outcome()).isNull();
        assertThat(failed.errorId()).isEqualTo(errorId);
        verify(cache).upsertOperation(failed);
    }
}
