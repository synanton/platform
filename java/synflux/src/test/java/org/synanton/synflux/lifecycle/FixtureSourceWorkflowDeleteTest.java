package org.synanton.synflux.lifecycle;

import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.IngestionRunRow;
import org.synanton.ingestioncache.domain.OperationRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceTombstoneRow;
import org.synanton.ingestioncache.domain.SyncResultRow;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixtureSourceWorkflowDeleteTest {

    private static final String TENANT = "tenant-a";
    private static final String SOURCE = "uploads";
    private static final String RESOURCE = "a/b.pdf";
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    @Test
    void deleteResource_uploadSource_recordsDeletionWithConfiguredConnector() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        OperationService operations = mock(OperationService.class);
        FixtureIngestionService lifecycle = mock(FixtureIngestionService.class);
        FixtureSourceWorkflow workflow = new FixtureSourceWorkflow(
            cache, null, null, null, null, null, null, null, null, null,
            lifecycle, operations, null, null, Clock.fixed(NOW, ZoneOffset.UTC));
        VerifiedIngestionContext context = new VerifiedIngestionContext(TENANT, "user", "USER");
        UUID operationId = UUID.randomUUID();
        SourceRow source = new SourceRow(
            TENANT, SOURCE, "manual-upload", "upload", "upload://uploads", Set.of(), "ACTIVE", NOW, null);
        OperationRow accepted = operation(operationId, "ACCEPTED");
        OperationRow running = operation(operationId, "RUNNING");
        IngestionRunRow deleted = new IngestionRunRow(
            TENANT, UUID.randomUUID(), operationId, SOURCE, "DELETED", NOW, NOW,
            new SyncResultRow(0, 0, 0, 1, 0, 0, 0, 0), null);
        OperationRow completed = operation(operationId, "SUCCEEDED");

        when(cache.readSource(TENANT, SOURCE)).thenReturn(Optional.of(source));
        when(operations.accept(eq(context), eq("DELETE /v1/sources/uploads/resources"),
            eq("request-1"), any())).thenReturn(accepted);
        when(cache.readIngestionRun(eq(TENANT), any(UUID.class))).thenReturn(Optional.empty());
        when(operations.markRunning(accepted, 1)).thenReturn(running);
        when(operations.cancellationRequested(TENANT, operationId)).thenReturn(false);
        when(lifecycle.recordDeletion(eq(context), any(UUID.class), eq(operationId),
            eq("manual-upload"), eq(SOURCE), eq(RESOURCE),
            eq(FixtureIngestionService.DeletionSignal.DELETED))).thenReturn(deleted);
        when(operations.completeFromRun(running, deleted)).thenReturn(completed);

        FixtureSourceWorkflow.Submission submission =
            workflow.deleteResource(context, SOURCE, RESOURCE, "request-1", null);

        assertThat(submission.run().status()).isEqualTo("DELETED");
        verify(lifecycle).recordDeletion(eq(context), any(UUID.class), eq(operationId),
            eq("manual-upload"), eq(SOURCE), eq(RESOURCE),
            eq(FixtureIngestionService.DeletionSignal.DELETED));
    }

    @Test
    void repeatDelete_alreadyTombstoned_returns404WithoutOperation() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        OperationService operations = mock(OperationService.class);
        FixtureIngestionService lifecycle = mock(FixtureIngestionService.class);
        FixtureSourceWorkflow workflow = new FixtureSourceWorkflow(
            cache, null, null, null, null, null, null, null, null, null,
            lifecycle, operations, null, null, Clock.fixed(NOW, ZoneOffset.UTC));
        VerifiedIngestionContext context = new VerifiedIngestionContext(TENANT, "user", "USER");
        SourceRow source = new SourceRow(
            TENANT, SOURCE, "manual-upload", "upload", "upload://uploads", Set.of(), "ACTIVE", NOW, null);
        when(cache.readSource(TENANT, SOURCE)).thenReturn(Optional.of(source));
        when(cache.readLatestSourceTombstone(TENANT, "manual-upload", SOURCE, RESOURCE)).thenReturn(
            Optional.of(new SourceTombstoneRow(TENANT, "manual-upload", SOURCE, RESOURCE,
                UUID.randomUUID(), NOW, "user", "USER", 0, UUID.randomUUID(), UUID.randomUUID())));
        when(cache.listSourceVersions(TENANT, "manual-upload", SOURCE, RESOURCE)).thenReturn(List.of());

        assertThatThrownBy(() -> workflow.deleteResource(context, SOURCE, RESOURCE, "request-2", null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(404));
        verify(operations, never()).accept(any(), any(), any(), any());
    }

    private static OperationRow operation(UUID operationId, String status) {
        return new OperationRow(
            TENANT, operationId, "INGESTION", status, "user", NOW, null, null, 0, 1,
            null, null, "request-1", null);
    }
}
