package org.synanton.synflux.lifecycle;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.common.jwt.SubjectAssertion;
import org.synanton.common.tenant.TenantContext;
import org.synanton.contentcache.ContentIntegrityException;
import org.synanton.contentcache.ContentStoreException;
import org.synanton.contentcache.ContentReference;
import org.synanton.contentcache.FileSystemContentStore;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.IngestionRunRow;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.KnowledgeProcessingRunRow;
import org.synanton.ingestioncache.domain.OperationRow;
import org.synanton.ingestioncache.domain.SourceIdentityConflictException;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.ingestioncache.domain.WorkflowStateRow;
import org.synanton.ingestioncache.domain.WorkflowStepRow;
import org.synanton.synflux.api.SourceController;
import org.synanton.synflux.config.SynfluxProperties;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synflux.pipeline.stage.AcquireStage;
import org.synanton.synflux.pipeline.stage.ExtractionStage;
import org.synanton.synflux.pipeline.stage.PersistStage;
import org.synanton.synvault.adapter.FilesystemAdapter;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ContentPullPort;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixtureIngestionLifecycleTest {

    private static CassandraContainer cassandra;
    private static CqlSession session;
    private static IngestionCacheClient cache;
    private static FixtureIngestionService lifecycle;

    @BeforeAll
    static void startCassandra() {
        cassandra = new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
        cassandra.start();
        InetSocketAddress contact = new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042));
        try (CqlSession admin = CqlSession.builder()
                .addContactPoint(contact).withLocalDatacenter("datacenter1").build()) {
            admin.execute("CREATE KEYSPACE IF NOT EXISTS ingestion_cache WITH replication = "
                + "{'class':'SimpleStrategy','replication_factor':1}");
        }
        session = CqlSession.builder().addContactPoint(contact)
            .withLocalDatacenter("datacenter1").withKeyspace("ingestion_cache").build();
        SchemaInstaller.install(session);
        cache = new IngestionCacheClient(session);
        lifecycle = new FixtureIngestionService(
            cache, Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
    }

    @AfterAll
    static void stopCassandra() {
        if (session != null) {
            session.close();
        }
        if (cassandra != null) {
            cassandra.stop();
        }
    }

    @Test
    void fixtureFirstUnchangedAndChangedIngest_preservesImmutableSourceHistory() {
        VerifiedIngestionContext context = principal("tenant-lifecycle");
        String sourceId = "fixture-corpus";
        String resourceId = "manuals/guide.md";
        Instant firstModified = Instant.parse("2026-10-10T10:00:00Z");
        ContentRef firstRef = fileRef("file:///fixture/manuals/guide.md", firstModified, 5);
        byte[] firstBytes = "first".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AtomicInteger publications = new AtomicInteger();

        IngestionRunRow first = ingest(context, sourceId, resourceId, firstRef, firstBytes,
            (ignored, versionId) -> {
                publications.incrementAndGet();
                return new FixtureIngestionService.PublishedContent(UUID.randomUUID());
            });
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.result().changed()).isEqualTo(1);
        List<SourceVersionRow> versionsAfterFirst = versions(context, sourceId, resourceId);
        assertThat(versionsAfterFirst).hasSize(1);
        SourceVersionRow firstVersion = versionsAfterFirst.getFirst();
        var source = cache.readSource(context.tenantId(), sourceId).orElseThrow();
        assertThat(source.connectorId()).isEqualTo("file");
        assertThat(source.status()).isEqualTo("ACTIVE");
        assertThat(source.lastSuccessfulSyncAt()).isEqualTo(Instant.parse("2026-10-17T12:00:00Z"));
        assertThat(cache.readSourceIdentity(context.tenantId(), "file", sourceId, resourceId)).isPresent();
        assertThat(cache.readIngestionManifest(context.tenantId(), first.runId()).orElseThrow().entryCount())
            .isEqualTo(1);
        assertThat(cache.listSourceOutbox(context.tenantId(), "file", sourceId, resourceId))
            .extracting(event -> event.eventType())
            .containsExactly("SourcePublished");

        IngestionRunRow unchanged = ingest(context, sourceId, resourceId, firstRef, firstBytes,
            (ignored, versionId) -> {
                publications.incrementAndGet();
                return new FixtureIngestionService.PublishedContent(UUID.randomUUID());
            });
        assertThat(unchanged.status()).isEqualTo("UNCHANGED");
        assertThat(unchanged.result().unchanged()).isEqualTo(1);
        assertThat(publications).hasValue(1);
        assertThat(versions(context, sourceId, resourceId)).containsExactly(firstVersion);
        assertThat(cache.listSourceOutbox(context.tenantId(), "file", sourceId, resourceId)).hasSize(1);

        ContentRef changedRef = fileRef("file:///fixture/manuals/guide.md",
            firstModified.plusSeconds(60), 6);
        byte[] changedBytes = "second".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        IngestionRunRow changed = ingest(context, sourceId, resourceId, changedRef, changedBytes,
            (ignored, versionId) -> new FixtureIngestionService.PublishedContent(UUID.randomUUID()));
        assertThat(changed.result().changed()).isEqualTo(1);
        assertThat(versions(context, sourceId, resourceId)).hasSize(2);
        assertThat(cache.readSourceVersion(context.tenantId(), "file", sourceId, resourceId,
            firstVersion.versionId())).contains(firstVersion);
        List<org.synanton.ingestioncache.domain.SourceOutboxRow> versionEvents =
            cache.listSourceOutbox(context.tenantId(), "file", sourceId, resourceId);
        assertThat(versionEvents).extracting(event -> event.eventType())
            .containsExactlyInAnyOrder("SourcePublished", "SourcePublished");
        assertThat(versionEvents).extracting(event -> event.eventId()).doesNotHaveDuplicates();
    }

    @Test
    void identicalContentUnderDistinctSourceIdentity_isNotMerged() {
        VerifiedIngestionContext context = principal("tenant-distinct-resources");
        Instant modified = Instant.parse("2026-10-11T10:00:00Z");
        byte[] bytes = "same bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID sharedContentRefId = UUID.randomUUID();
        ingest(context, "fixture-corpus", "a/guide.md", fileRef("file:///a/guide.md", modified, bytes.length),
            bytes, (ignored, versionId) -> new FixtureIngestionService.PublishedContent(sharedContentRefId));
        ingest(context, "fixture-corpus", "b/guide.md", fileRef("file:///b/guide.md", modified, bytes.length),
            bytes, (ignored, versionId) -> new FixtureIngestionService.PublishedContent(sharedContentRefId));

        SourceVersionRow first = versions(context, "fixture-corpus", "a/guide.md").getFirst();
        SourceVersionRow second = versions(context, "fixture-corpus", "b/guide.md").getFirst();
        assertThat(first.contentDigest()).isEqualTo(second.contentDigest());
        assertThat(first.contentRefId()).isEqualTo(second.contentRefId());
        assertThat(first.versionId()).isNotEqualTo(second.versionId());
        assertThat(first.resourceId()).isNotEqualTo(second.resourceId());
    }

    @Test
    void tenantScopeSeparatesTheSameConfiguredSourceAndResource() {
        VerifiedIngestionContext firstTenant = principal("tenant-source-owner-a");
        VerifiedIngestionContext secondTenant = principal("tenant-source-owner-b");
        Instant modified = Instant.parse("2026-10-11T11:00:00Z");
        byte[] bytes = "tenant-owned".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentRef ref = fileRef("file:///shared/guide.md", modified, bytes.length);
        UUID sharedContentRefId = UUID.randomUUID();

        ingest(firstTenant, "fixture-corpus", "guide.md", ref, bytes, (ignored, versionId) -> new FixtureIngestionService.PublishedContent(sharedContentRefId));
        ingest(secondTenant, "fixture-corpus", "guide.md", ref, bytes, (ignored, versionId) -> new FixtureIngestionService.PublishedContent(sharedContentRefId));

        SourceVersionRow first = versions(firstTenant, "fixture-corpus", "guide.md").getFirst();
        SourceVersionRow second = versions(secondTenant, "fixture-corpus", "guide.md").getFirst();
        assertThat(first.contentRefId()).isEqualTo(second.contentRefId());
        assertThat(first.versionId()).isNotEqualTo(second.versionId());
        assertThat(first.tenantId()).isNotEqualTo(second.tenantId());

        lifecycle.recordDeletion(firstTenant, UUID.randomUUID(), UUID.randomUUID(), "file",
            "fixture-corpus", "guide.md", FixtureIngestionService.DeletionSignal.DELETED);
        assertThat(cache.readLatestSourceTombstone(firstTenant.tenantId(), "file", "fixture-corpus", "guide.md"))
            .isPresent();
        assertThat(cache.readLatestSourceTombstone(secondTenant.tenantId(), "file", "fixture-corpus", "guide.md"))
            .isEmpty();
    }

    @Test
    void reusedModifiedTimeAndSizeWithDifferentBytes_failsAsRevisionConflict() {
        VerifiedIngestionContext context = principal("tenant-revision-conflict");
        String sourceId = "fixture-corpus";
        String resourceId = "conflict/document.md";
        Instant modified = Instant.parse("2026-10-11T12:00:00Z");
        ContentRef ref = fileRef("file:///conflict/document.md", modified, 4);
        ingest(context, sourceId, resourceId, ref, "one!".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            (ignored, versionId) -> new FixtureIngestionService.PublishedContent(UUID.randomUUID()));

        IngestionRunRow conflict = ingest(context, sourceId, resourceId, ref,
            "two?".getBytes(java.nio.charset.StandardCharsets.UTF_8), (ignored, versionId) -> new FixtureIngestionService.PublishedContent(UUID.randomUUID()));
        var error = cache.readIngestionError(context.tenantId(), UUID.fromString(conflict.errorRef())).orElseThrow();
        assertThat(conflict.status()).isEqualTo("FAILED");
        assertThat(error.code()).isEqualTo("RESOURCE_CONFLICT");
        assertThat(error.retryable()).isFalse();
        assertThat(versions(context, sourceId, resourceId)).hasSize(1);
    }

    @Test
    void tenantUniqueSourceIdCannotBeReboundToAnotherConnector() {
        String tenantId = "tenant-source-config-conflict";
        Instant createdAt = Instant.parse("2026-10-13T10:00:00Z");
        cache.createSourceIfAbsent(new SourceRow(
            tenantId, "configured-source", "file", "filesystem", "file:///fixture", Set.of(), "ACTIVE",
            createdAt, null
        ));

        assertThatThrownBy(() -> cache.createSourceIfAbsent(new SourceRow(
            tenantId, "configured-source", "drive", "drive", "drive://other", Set.of(), "ACTIVE", createdAt, null
        ))).isInstanceOf(SourceIdentityConflictException.class);
    }

    @Test
    void concurrentSameIdempotencyKeyReservationsConvergeOnOneOperation() throws Exception {
        VerifiedIngestionContext context = principal("tenant-operation-race-" + UUID.randomUUID());
        OperationService operationService = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return operationService.accept(context, "request-a", "same-key");
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return operationService.accept(context, "request-b", "same-key");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            OperationRow operationA = first.get(10, TimeUnit.SECONDS);
            OperationRow operationB = second.get(10, TimeUnit.SECONDS);
            assertThat(operationA.operationId()).isEqualTo(operationB.operationId());
            assertThat(cache.readOperation(context.tenantId(), operationA.operationId())).isPresent();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void incompleteDiscoveryDoesNotTombstone_butExplicitDeletionPreservesProvenance() {
        VerifiedIngestionContext context = principal("tenant-deletion");
        String sourceId = "fixture-corpus";
        String resourceId = "legal/policy.md";
        byte[] bytes = "policy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Instant modified = Instant.parse("2026-10-12T10:00:00Z");
        ingest(context, sourceId, resourceId, fileRef("file:///legal/policy.md", modified, bytes.length),
            bytes, (ignored, versionId) -> new FixtureIngestionService.PublishedContent(UUID.randomUUID()));

        IngestionRunRow incomplete = lifecycle.recordDeletion(
            context, UUID.randomUUID(), UUID.randomUUID(), "file", sourceId, resourceId,
            FixtureIngestionService.DeletionSignal.SYNC_INCOMPLETE);
        assertThat(incomplete.result().skipped()).isEqualTo(1);
        assertThat(cache.readLatestSourceTombstone(context.tenantId(), "file", sourceId, resourceId)).isEmpty();

        UUID deletionOperationId = UUID.randomUUID();
        IngestionRunRow deleted = lifecycle.recordDeletion(
            context, UUID.randomUUID(), deletionOperationId, "file", sourceId, resourceId,
            FixtureIngestionService.DeletionSignal.DELETED);
        assertThat(deleted.status())
            .as("deletion failure details: %s", deleted.errorRef() == null ? "none"
                : cache.readIngestionError(context.tenantId(), UUID.fromString(deleted.errorRef())).orElseThrow())
            .isEqualTo("DELETED");
        var tombstone = cache.readLatestSourceTombstone(context.tenantId(), "file", sourceId, resourceId)
            .orElseThrow();
        assertThat(tombstone.deletedByPrincipalId()).isEqualTo(context.principalId());
        assertThat(tombstone.deletedByPrincipalType()).isEqualTo("CONNECTOR");
        assertThat(tombstone.versionCount()).isEqualTo(1);
        SourceVersionRow previous = versions(context, sourceId, resourceId).getFirst();
        UUID previousVersion = previous.versionId();
        assertThat(cache.readVersionManifest(context.tenantId(), tombstone.versionsManifestRef()))
            .containsExactly(previousVersion);
        assertThat(tombstone.appliesTo(previous)).isTrue();
        lifecycle.recordDeletion(context, UUID.randomUUID(), deletionOperationId, "file", sourceId, resourceId,
            FixtureIngestionService.DeletionSignal.DELETED);
        assertThat(cache.listSourceOutbox(context.tenantId(), "file", sourceId, resourceId).stream()
            .filter(event -> "SourceDeleted".equals(event.eventType())))
            .hasSize(1);

        FixtureIngestionService afterDeletion = new FixtureIngestionService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:01Z"), ZoneOffset.UTC));
        IngestionRunRow recreated = afterDeletion.ingest(
            context, UUID.randomUUID(), UUID.randomUUID(), "file", sourceId, resourceId,
            fileRef("file:///legal/policy.md", modified, bytes.length), bytes,
            testReference(context.tenantId()), (ignored, versionId) -> new FixtureIngestionService.PublishedContent(UUID.randomUUID()));
        assertThat(recreated.result().changed()).isEqualTo(1);
        List<SourceVersionRow> allVersions = versions(context, sourceId, resourceId);
        assertThat(allVersions).extracting(SourceVersionRow::versionId)
            .contains(previousVersion)
            .hasSize(2);
        SourceVersionRow recreatedVersion = allVersions.stream()
            .filter(version -> !version.versionId().equals(previousVersion)).findFirst().orElseThrow();
        assertThat(tombstone.appliesTo(recreatedVersion)).isFalse();
        assertThat(cache.readLatestSourceTombstone(context.tenantId(), "file", sourceId, resourceId)).isPresent();
    }

    @Test
    void failedPublicationIsObservableAndRetryCanComplete() {
        VerifiedIngestionContext context = principal("tenant-retry");
        String sourceId = "fixture-corpus";
        String resourceId = "retry/readme.md";
        byte[] bytes = "retry me".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentRef ref = fileRef("file:///retry/readme.md", null, bytes.length);
        UUID operationId = UUID.randomUUID();
        IngestionRunRow failed = lifecycle.ingest(context, UUID.randomUUID(), operationId, ref.scheme(), sourceId,
            resourceId, ref, bytes, testReference(context.tenantId()),
            (ignored, versionId) -> { throw new IOException("fixture publish outage"); });
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.result().failed()).isEqualTo(1);
        var error = cache.readIngestionError(context.tenantId(), UUID.fromString(failed.errorRef())).orElseThrow();
        assertThat(error.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(error.status()).isEqualTo(503);
        assertThat(error.retryable()).isTrue();

        IngestionRunRow retried = lifecycle.ingest(context, UUID.randomUUID(), operationId, ref.scheme(), sourceId,
            resourceId, ref, bytes, testReference(context.tenantId()), (ignored, versionId) -> new FixtureIngestionService.PublishedContent(UUID.randomUUID()));
        assertThat(retried.status()).isEqualTo("COMPLETED");
        assertThat(retried.operationId()).isEqualTo(operationId);
        assertThat(versions(context, sourceId, resourceId)).hasSize(1);
    }

    @Test
    void contentIntegrityFailureAfterDiscoveryIsQuarantinedWithoutPublishingSourceVersion(
            @TempDir Path fixtureRoot) throws IOException {
        Path document = fixtureRoot.resolve("quarantine/document.md");
        Files.createDirectories(document.getParent());
        Files.writeString(document, "untrusted content");
        ContentPullPort pullPort = new ContentPullPort(List.of(new FilesystemAdapter()));
        AcquireStage acquire = mock(AcquireStage.class);
        when(acquire.apply(any(), any())).thenThrow(new ContentIntegrityException("round-trip digest mismatch"));
        ExtractionStage extraction = mock(ExtractionStage.class);
        PersistStage persist = mock(PersistStage.class);
        PipelineStage<ParsedDocument, ChunkedDocument> chunk = new PipelineStage<>() {
            @Override
            public String name() { return "test-chunk"; }
            @Override
            public ChunkedDocument apply(ParsedDocument parsed, StageContext context) {
                return new ChunkedDocument(parsed, List.of());
            }
        };
        PipelineStage<ChunkedDocument, ChunkedDocument> identityStage = new PipelineStage<>() {
            @Override
            public String name() { return "test-identity"; }
            @Override
            public ChunkedDocument apply(ChunkedDocument value, StageContext context) {
                return value;
            }
        };

        String tenantId = "tenant-quarantine-" + UUID.randomUUID();
        String sourceId = "quarantine-source-" + UUID.randomUUID();
        cache.createSourceIfAbsent(new SourceRow(
            tenantId, sourceId, "filesystem", "file", fixtureRoot.toUri().toString(), Set.of(), "ACTIVE",
            Instant.parse("2026-10-17T11:59:00Z"), null));
        OperationService operationService = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        FixtureSourceWorkflow workflow = new FixtureSourceWorkflow(
            cache, pullPort, acquire, extraction, chunk, identityStage, identityStage, identityStage,
            persist, testProperties(), lifecycle, operationService, Runnable::run,
            new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));

        FixtureSourceWorkflow.Submission submission = workflow.ingest(
            principal(tenantId), sourceId, "quarantine/document.md", "request-quarantine", null);
        IngestionRunRow run = cache.readIngestionRun(tenantId, submission.run().runId()).orElseThrow();
        OperationRow operation = cache.readOperation(tenantId, submission.operation().operationId()).orElseThrow();
        var entries = cache.listManifestEntries(tenantId, run.runId());
        var error = cache.readIngestionError(tenantId, UUID.fromString(run.errorRef())).orElseThrow();

        assertThat(run.status()).isEqualTo("QUARANTINED");
        assertThat(run.result().quarantined()).isEqualTo(1);
        assertThat(run.result().failed()).isZero();
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.result()).isEqualTo("QUARANTINED");
            assertThat(entry.sourceVersionId()).isNull();
            assertThat(entry.errorRef()).isEqualTo(run.errorRef());
        });
        assertThat(error.code()).isEqualTo("OPERATION_FAILED");
        assertThat(error.retryable()).isFalse();
        assertThat(error.detailsJson()).contains("\"reason\":\"QUARANTINED\"", "\"quarantined\":1");
        assertThat(operation.status()).isEqualTo("FAILED");
        assertThat(operation.errorId()).isEqualTo(error.errorId());
        assertThat(cache.listSourceVersions(tenantId, "filesystem", sourceId, "quarantine/document.md"))
            .isEmpty();
        assertThat(cache.listSourceOutbox(tenantId, "filesystem", sourceId, "quarantine/document.md"))
            .isEmpty();
    }

    @Test
    void missingContentCacheReferenceIsQuarantinedAndNeverPublished() {
        VerifiedIngestionContext context = principal("tenant-reference-invariant");
        byte[] bytes = "unverifiable provenance".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentRef ref = fileRef("file:///invariant/guide.md", null, bytes.length);

        // Legacy overload delegates with a blank Content Cache reference: publication
        // must be withheld, never persisted with an empty reference.
        IngestionRunRow run = lifecycle.ingest(context, UUID.randomUUID(), UUID.randomUUID(),
            "invariant-source", "guide.md", ref, bytes, (ignored, versionId) -> new FixtureIngestionService.PublishedContent(UUID.randomUUID()));
        var entries = cache.listManifestEntries(context.tenantId(), run.runId());
        var error = cache.readIngestionError(context.tenantId(), UUID.fromString(run.errorRef())).orElseThrow();

        assertThat(run.status()).isEqualTo("QUARANTINED");
        assertThat(run.result().quarantined()).isEqualTo(1);
        assertThat(run.result().failed()).isZero();
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.result()).isEqualTo("QUARANTINED");
            assertThat(entry.sourceVersionId()).isNull();
        });
        assertThat(error.code()).isEqualTo("OPERATION_FAILED");
        assertThat(error.retryable()).isFalse();
        assertThat(error.detailsJson()).contains("\"reason\":\"QUARANTINED\"");
        assertThat(cache.listSourceVersions(context.tenantId(), "file", "invariant-source", "guide.md"))
            .isEmpty();
        assertThat(cache.listSourceOutbox(context.tenantId(), "file", "invariant-source", "guide.md"))
            .isEmpty();
    }

    @Test
    void cancellationRecordsCancelledDispositionWithoutPublishingVersion() {
        VerifiedIngestionContext context = principal("tenant-cancelled-run");
        byte[] bytes = "cancel me".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        IngestionRunRow cancelled = lifecycle.ingest(
            context, UUID.randomUUID(), UUID.randomUUID(), "file", "fixture", "cancel/readme.md",
            fileRef("file:///cancel/readme.md", Instant.parse("2026-10-15T10:00:00Z"), bytes.length),
            bytes, testReference(context.tenantId()),
            (ignored, versionId) -> { throw new CancellationException("cancel requested"); });
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.result().cancelled()).isEqualTo(1);
        assertThat(cancelled.errorRef()).isNull();
        assertThat(versions(context, "fixture", "cancel/readme.md")).isEmpty();
        assertThat(cache.listManifestEntries(context.tenantId(), cancelled.runId()))
            .extracting(entry -> entry.result())
            .containsExactly("CANCELLED");
    }

    @Test
    void emptyRunManifestPreservesTerminalStatusAndPreDiscoveryExpiry() {
        VerifiedIngestionContext context = principal("tenant-empty-runs");
        var emptySource = lifecycle.recordEmptyRun(context, UUID.randomUUID(), UUID.randomUUID(),
            "empty-source", FixtureIngestionService.EmptyRunDisposition.EMPTY_SOURCE);
        var unchangedSource = lifecycle.recordEmptyRun(context, UUID.randomUUID(), UUID.randomUUID(),
            "unchanged-source", FixtureIngestionService.EmptyRunDisposition.UNCHANGED_SOURCE);
        var skipped = lifecycle.recordEmptyRun(context, UUID.randomUUID(), UUID.randomUUID(),
            "policy-source", FixtureIngestionService.EmptyRunDisposition.SKIPPED_POLICY);
        var cancelled = lifecycle.recordEmptyRun(context, UUID.randomUUID(), UUID.randomUUID(),
            "cancelled-source", FixtureIngestionService.EmptyRunDisposition.CANCELLED);
        var failed = lifecycle.recordEmptyRun(context, UUID.randomUUID(), UUID.randomUUID(),
            "failed-source", FixtureIngestionService.EmptyRunDisposition.FAILED_BEFORE_DISCOVERY);

        assertThat(emptySource.status()).isEqualTo("COMPLETED");
        assertThat(unchangedSource.status()).isEqualTo("UNCHANGED");
        assertThat(skipped.status()).isEqualTo("SKIPPED");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(failed.status()).isEqualTo("FAILED");
        for (IngestionRunRow run : List.of(emptySource, unchangedSource, skipped, cancelled, failed)) {
            assertThat(run.result().entryCount()).isZero();
            assertThat(cache.readIngestionManifest(context.tenantId(), run.runId()).orElseThrow().entryCount())
                .isZero();
            assertThat(cache.listManifestEntries(context.tenantId(), run.runId())).isEmpty();
        }
        assertThat(cache.readIngestionError(context.tenantId(), UUID.fromString(failed.errorRef()))).isPresent();

        UUID expiredRunId = UUID.randomUUID();
        UUID expiredOperationId = UUID.randomUUID();
        var expired = lifecycle.recordEmptyRun(context, expiredRunId, expiredOperationId,
            "expired-source", FixtureIngestionService.EmptyRunDisposition.EXPIRED_BEFORE_DISCOVERY);
        OperationRow accepted = new OperationRow(context.tenantId(), expiredOperationId, "INGESTION", "ACCEPTED",
            context.principalId(), Instant.parse("2026-10-17T11:59:00Z"), null, null,
            0, 0, null, null, "request-expired", null);
        cache.createOperationIfAbsent(accepted);
        OperationRow expiredOperation = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC))
            .expireBeforeDiscovery(accepted);
        assertThat(expired.status()).isEqualTo("SCHEDULED");
        assertThat(expired.completedAt()).isNull();
        assertThat(expired.result()).isNull();
        assertThat(expiredOperation.status()).isEqualTo("EXPIRED");
        assertThat(cache.readIngestionManifest(context.tenantId(), expired.runId())).isEmpty();
    }

    @Test
    void filesystemAdapterFixtureUsesConnectorRelativeResourceId(@TempDir Path fixtureRoot) throws IOException {
        Path nested = fixtureRoot.resolve("manuals/quick start.md");
        Files.createDirectories(nested.getParent());
        byte[] bytes = "fixture content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(nested, bytes);
        FilesystemAdapter adapter = new FilesystemAdapter();
        ContentRef ref = adapter.list(fixtureRoot.toUri().toString()).findFirst().orElseThrow();
        Path acquiredPath = Path.of(URI.create(ref.uri()));
        String resourceId = fixtureRoot.relativize(acquiredPath).toString().replace('\\', '/');
        VerifiedIngestionContext context = principal("tenant-filesystem-fixture");
        byte[] acquiredBytes;
        try (var input = adapter.open(ref)) {
            acquiredBytes = input.readAllBytes();
        }

        IngestionRunRow run = lifecycle.ingest(context, UUID.randomUUID(), UUID.randomUUID(), ref.scheme(),
            "local-fixture", resourceId, ref, acquiredBytes, testReference(context.tenantId()),
            (ignored, versionId) -> new FixtureIngestionService.PublishedContent(UUID.randomUUID()));

        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(versions(context, "local-fixture", "manuals/quick start.md")).hasSize(1);
    }

    @Test
    void tenantScopedWorkflowUsesRealFilesystemAcquireAndOperationIdempotency(@TempDir Path fixtureRoot)
            throws IOException {
        Path document = fixtureRoot.resolve("manuals/workflow.md");
        Files.createDirectories(document.getParent());
        Files.writeString(document, "fixture workflow text");
        FilesystemAdapter adapter = new FilesystemAdapter();
        ContentPullPort pullPort = new ContentPullPort(List.of(adapter));
        FileSystemContentStore contentStore = new FileSystemContentStore(
            fixtureRoot.resolveSibling(fixtureRoot.getFileName() + "-content-cache"));
        AcquireStage acquire = new AcquireStage(pullPort, contentStore);
        ExtractionStage extraction = mock(ExtractionStage.class);
        PersistStage persist = new PersistStage(cache);
        PipelineStage<ParsedDocument, ChunkedDocument> chunk = new PipelineStage<>() {
            @Override
            public String name() { return "test-chunk"; }
            @Override
            public ChunkedDocument apply(ParsedDocument parsed, StageContext context) {
                return new ChunkedDocument(parsed, List.of());
            }
        };
        PipelineStage<ChunkedDocument, ChunkedDocument> identityStage = new PipelineStage<>() {
            @Override
            public String name() { return "test-identity"; }
            @Override
            public ChunkedDocument apply(ChunkedDocument document, StageContext context) {
                return document;
            }
        };
        when(extraction.apply(any(), any())).thenAnswer(invocation -> {
            var acquired = invocation.<org.synanton.synflux.domain.AcquiredDocument>getArgument(0);
            return new ParsedDocument(acquired, new String(acquired.bytes(), java.nio.charset.StandardCharsets.UTF_8),
                Map.of(), null);
        });

        String tenantId = "tenant-workflow-" + UUID.randomUUID();
        String sourceId = "workflow-fixture-" + UUID.randomUUID();
        cache.createSourceIfAbsent(new SourceRow(
            tenantId, sourceId, "filesystem", "file", fixtureRoot.toUri().toString(),
            Set.of("DISCOVER"), "ACTIVE", Instant.parse("2026-10-17T11:59:00Z"), null
        ));
        SynfluxProperties properties = testProperties();
        OperationService operationService = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        FixtureSourceWorkflow workflow = new FixtureSourceWorkflow(
            cache, pullPort, acquire, extraction, chunk, identityStage, identityStage, identityStage,
            persist, properties, lifecycle, operationService, Runnable::run,
            new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        VerifiedIngestionContext context = principal(tenantId);

        FixtureSourceWorkflow.Submission first = workflow.ingest(
            context, sourceId, "manuals/workflow.md", "request-workflow", "workflow-key");
        FixtureSourceWorkflow.Submission retry = workflow.ingest(
            context, sourceId, "manuals/workflow.md", "request-retry", "workflow-key");
        FixtureSourceWorkflow.Submission accepted = workflow.submit(
            context, sourceId, "manuals/workflow.md", "request-submit", null);
        IngestionRunRow submittedRun = cache.readIngestionRun(context.tenantId(), accepted.run().runId()).orElseThrow();
        OperationRow submittedOperation = cache.readOperation(context.tenantId(), accepted.operation().operationId())
            .orElseThrow();

        assertThat(first.run().status())
            .as("workflow failure details: %s", first.run().errorRef() == null ? "none"
                : cache.readIngestionError(context.tenantId(), UUID.fromString(first.run().errorRef())).orElseThrow())
            .isEqualTo("COMPLETED");
        assertThat(first.operation().status()).isEqualTo("SUCCEEDED");
        assertThat(first.operation().outcome()).isEqualTo("FULL");
        assertThat(retry.run().runId()).isEqualTo(first.run().runId());
        assertThat(retry.operation().operationId()).isEqualTo(first.operation().operationId());
        assertThat(submittedRun.status()).isEqualTo("UNCHANGED");
        assertThat(submittedRun.operationId()).isEqualTo(accepted.operation().operationId());
        assertThat(submittedOperation.status()).isEqualTo("SUCCEEDED");
        assertThat(submittedOperation.outcome()).isEqualTo("FULL");
        assertThat(cache.listSourceVersions(context.tenantId(), "filesystem", sourceId, "manuals/workflow.md"))
            .hasSize(1);
        SourceVersionRow sourceVersion = cache.listSourceVersions(
            context.tenantId(), "filesystem", sourceId, "manuals/workflow.md").getFirst();
        assertThat(sourceVersion.contentCacheReference()).isNotBlank();
        FileSystemContentStore reopenedContentStore = new FileSystemContentStore(contentStore.root());
        assertThat(cache.readManifest(context.tenantId(), sourceVersion.contentRefId()).orElseThrow()
            .contentCacheReference()).isEqualTo(sourceVersion.contentCacheReference());
        TenantContext.set(new SubjectAssertion("alice", -1, List.of(), tenantId,
            Instant.parse("2026-10-17T12:00:00Z")));
        try {
            SourceController.SourceVersionResource apiVersion = new SourceController(
                cache, Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC), workflow,
                new FilesystemFixturePolicy(fixtureRoot)).listVersions(sourceId).getFirst();
            assertThat(apiVersion.contentCacheReference()).isEqualTo(sourceVersion.contentCacheReference());
            assertThat(reopenedContentStore.get(ContentReference.parse(apiVersion.contentCacheReference())))
                .isEqualTo(Files.readAllBytes(document));
        } finally {
            TenantContext.clear();
        }
        assertThat(cache.listSourceOutbox(context.tenantId(), "filesystem", sourceId, "manuals/workflow.md"))
            .extracting(event -> event.eventType())
            .containsExactly("SourcePublished");
    }

    @Test
    void concurrentSameKeySubmissionsConvergeOnOneOperationAndRun(@TempDir Path fixtureRoot) throws Exception {
        Path document = fixtureRoot.resolve("manuals/concurrent.md");
        Files.createDirectories(document.getParent());
        Files.writeString(document, "concurrent submission");
        ContentPullPort pullPort = new ContentPullPort(List.of(new FilesystemAdapter()));
        FileSystemContentStore contentStore = new FileSystemContentStore(
            fixtureRoot.resolveSibling(fixtureRoot.getFileName() + "-concurrent-cache"));
        ExtractionStage extraction = mock(ExtractionStage.class);
        PersistStage persist = mock(PersistStage.class);
        CountDownLatch extractionStarted = new CountDownLatch(1);
        CountDownLatch finishExtraction = new CountDownLatch(1);
        when(extraction.apply(any(), any())).thenAnswer(invocation -> {
            extractionStarted.countDown();
            if (!finishExtraction.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test extraction release timed out");
            }
            var acquired = invocation.<org.synanton.synflux.domain.AcquiredDocument>getArgument(0);
            return new ParsedDocument(acquired, "concurrent text", Map.of(), null);
        });
        when(persist.apply(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        PipelineStage<ParsedDocument, ChunkedDocument> chunk = new PipelineStage<>() {
            @Override
            public String name() { return "test-chunk"; }
            @Override
            public ChunkedDocument apply(ParsedDocument parsed, StageContext context) {
                return new ChunkedDocument(parsed, List.of());
            }
        };
        PipelineStage<ChunkedDocument, ChunkedDocument> identityStage = new PipelineStage<>() {
            @Override
            public String name() { return "test-identity"; }
            @Override
            public ChunkedDocument apply(ChunkedDocument value, StageContext context) {
                return value;
            }
        };

        String tenantId = "tenant-concurrent-submit-" + UUID.randomUUID();
        String sourceId = "concurrent-source-" + UUID.randomUUID();
        cache.createSourceIfAbsent(new SourceRow(
            tenantId, sourceId, "filesystem", "file", fixtureRoot.toUri().toString(), Set.of(), "ACTIVE",
            Instant.parse("2026-10-17T11:59:00Z"), null));
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        OperationService operationService = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        FixtureSourceWorkflow workflow = new FixtureSourceWorkflow(
            cache, pullPort, new AcquireStage(pullPort, contentStore), extraction, chunk,
            identityStage, identityStage, identityStage, persist, testProperties(), lifecycle,
            operationService, workerExecutor, new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        FixtureSourceWorkflow duplicateWorkflow = new FixtureSourceWorkflow(
            cache, pullPort, new AcquireStage(pullPort, contentStore), extraction, chunk,
            identityStage, identityStage, identityStage, persist, testProperties(), lifecycle,
            new OperationService(cache, Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC)),
            Runnable::run, new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        VerifiedIngestionContext context = principal(tenantId);

        try {
            FixtureSourceWorkflow.Submission first = workflow.submit(
                context, sourceId, "manuals/concurrent.md", "request-first", "concurrent-key");
            assertThat(extractionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            FixtureSourceWorkflow.Submission retry = duplicateWorkflow.submit(
                context, sourceId, "manuals/concurrent.md", "request-retry", "concurrent-key");
            finishExtraction.countDown();

            IngestionRunRow terminalRun = null;
            OperationRow terminalOperation = null;
            for (int attempt = 0; attempt < 100; attempt++) {
                terminalRun = cache.readIngestionRun(tenantId, first.run().runId()).orElseThrow();
                terminalOperation = cache.readOperation(tenantId, first.operation().operationId()).orElseThrow();
                if (terminalRun.completedAt() != null && "SUCCEEDED".equals(terminalOperation.status())) {
                    break;
                }
                Thread.sleep(20);
            }

            assertThat(retry.operation().operationId()).isEqualTo(first.operation().operationId());
            assertThat(retry.run().runId()).isEqualTo(first.run().runId());
            assertThat(terminalRun.status()).isEqualTo("COMPLETED");
            assertThat(terminalRun.operationId()).isEqualTo(terminalOperation.operationId());
            assertThat(terminalOperation.status()).isEqualTo("SUCCEEDED");
            assertThat(session.execute(SimpleStatement.newInstance(
                "SELECT run_id FROM ingestion_cache.ingestion_runs WHERE tenant_id=?", tenantId)).all())
                .hasSize(1);
            assertThat(cache.listSourceOutbox(
                tenantId, "filesystem", sourceId, "manuals/concurrent.md")).hasSize(1);
        } finally {
            finishExtraction.countDown();
            workerExecutor.shutdownNow();
        }
    }

    @Test
    void knowledgeChunksCarryProvenanceToSourceVersionAndContentArtifact(@TempDir Path fixtureRoot)
            throws IOException {
        Path document = fixtureRoot.resolve("knowledge/provenance.md");
        Files.createDirectories(document.getParent());
        Files.writeString(document, "canonical knowledge with provenance");
        FilesystemAdapter adapter = new FilesystemAdapter();
        ContentPullPort pullPort = new ContentPullPort(List.of(adapter));
        FileSystemContentStore contentStore = new FileSystemContentStore(
            fixtureRoot.resolveSibling(fixtureRoot.getFileName() + "-knowledge-cache"));
        AcquireStage acquire = new AcquireStage(pullPort, contentStore);
        ExtractionStage extraction = mock(ExtractionStage.class);
        PersistStage persist = new PersistStage(cache);
        PipelineStage<ParsedDocument, ChunkedDocument> chunk = new PipelineStage<>() {
            @Override
            public String name() { return "test-chunk"; }
            @Override
            public ChunkedDocument apply(ParsedDocument parsed, StageContext context) {
                var acquired = parsed.acquired();
                var knowledgeChunk = new SemanticChunk("k0", acquired.contentRefId().toString(), 0,
                    SemanticChunk.ChunkType.SECTION, "knowledge text", null, List.of("Section"), "Section",
                    List.of("element-1", "element-2"), 1, 1, 3, false, Map.of(),
                    SemanticChunk.PUBLIC_ONLY, "chunksha");
                return new ChunkedDocument(parsed, List.of(knowledgeChunk));
            }
        };
        PipelineStage<ChunkedDocument, ChunkedDocument> identityStage = new PipelineStage<>() {
            @Override
            public String name() { return "test-identity"; }
            @Override
            public ChunkedDocument apply(ChunkedDocument value, StageContext context) {
                return value;
            }
        };
        when(extraction.apply(any(), any())).thenAnswer(invocation -> {
            var acquired = invocation.<org.synanton.synflux.domain.AcquiredDocument>getArgument(0);
            return new ParsedDocument(acquired, "knowledge text", Map.of(), null);
        });

        String tenantId = "tenant-knowledge-" + UUID.randomUUID();
        String sourceId = "knowledge-source-" + UUID.randomUUID();
        cache.createSourceIfAbsent(new SourceRow(
            tenantId, sourceId, "filesystem", "file", fixtureRoot.toUri().toString(),
            Set.of("DISCOVER"), "ACTIVE", Instant.parse("2026-10-17T11:59:00Z"), null
        ));
        OperationService operationService = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        FixtureSourceWorkflow workflow = new FixtureSourceWorkflow(
            cache, pullPort, acquire, extraction, chunk, identityStage, identityStage, identityStage,
            persist, testProperties(), lifecycle, operationService, Runnable::run,
            new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));

        FixtureSourceWorkflow.Submission submission = workflow.ingest(
            principal(tenantId), sourceId, "knowledge/provenance.md", "request-knowledge", "knowledge-key");
        SourceVersionRow sourceVersion = cache.listSourceVersions(
            tenantId, "filesystem", sourceId, "knowledge/provenance.md").getFirst();
        List<ChunkRow> knowledgeChunks = cache.readChunks(tenantId, sourceVersion.contentRefId());
        assertThat(knowledgeChunks).isNotEmpty();
        for (ChunkRow knowledgeChunk : knowledgeChunks) {
            assertThat(knowledgeChunk.sourceVersionIds()).containsExactly(sourceVersion.versionId());
            assertThat(knowledgeChunk.contentRefId()).isEqualTo(sourceVersion.contentRefId());
            assertThat(knowledgeChunk.processingRunId()).isNotNull();
        }
        // Both artifact references resolve: the source version's content_ref (chunk
        // partition key) and the manifest's content_cache_reference (opaque handle).
        var manifest = cache.readManifest(tenantId, sourceVersion.contentRefId()).orElseThrow();
        assertThat(manifest.contentCacheReference()).isEqualTo(sourceVersion.contentCacheReference());
        assertThat(manifest.processingRunId()).isEqualTo(knowledgeChunks.getFirst().processingRunId());

        KnowledgeProcessingRunRow run = cache.readKnowledgeProcessingRun(
            tenantId, knowledgeChunks.getFirst().processingRunId()).orElseThrow();
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.producer()).isEqualTo("synflux-knowledge");
        assertThat(run.inputSourceVersionIds()).containsExactly(sourceVersion.versionId());
        assertThat(run.inputJson()).contains(
            sourceVersion.contentRefId().toString(), sourceVersion.contentCacheReference());
        assertThat(run.outputJson()).contains("chunk_count");
        // Reprocessing the same source version does not duplicate derived state.
        assertThat(cache.readChunks(tenantId, sourceVersion.contentRefId()))
            .hasSize(knowledgeChunks.size());
        assertThat(cache.listSourceVersions(tenantId, "filesystem", sourceId, "knowledge/provenance.md"))
            .hasSize(1);
        assertThat(submission.run().status()).isEqualTo("COMPLETED");

        // Identical bytes under a second resource derive the same content-addressed
        // chunks: the chunk is attributed to BOTH source versions, and each version's
        // provenance path resolves without forking the deduped artifact.
        Files.writeString(fixtureRoot.resolve("knowledge/provenance-copy.md"), "canonical knowledge with provenance");
        workflow.ingest(principal(tenantId), sourceId, "knowledge/provenance-copy.md",
            "request-knowledge-copy", "knowledge-copy-key");
        SourceVersionRow secondVersion = cache.listSourceVersions(
            tenantId, "filesystem", sourceId, "knowledge/provenance-copy.md").getFirst();
        assertThat(secondVersion.contentRefId()).isEqualTo(sourceVersion.contentRefId());
        List<ChunkRow> sharedChunks = cache.readChunks(tenantId, sourceVersion.contentRefId());
        for (ChunkRow knowledgeChunk : sharedChunks) {
            assertThat(knowledgeChunk.sourceVersionIds())
                .containsExactlyInAnyOrder(sourceVersion.versionId(), secondVersion.versionId());
        }
        // The chunk is the accumulator: two runs exist (one per observing version),
        // each recording only the source version it processed.
        KnowledgeProcessingRunRow runForCopy = cache.readKnowledgeProcessingRun(
            tenantId, sharedChunks.getFirst().processingRunId()).orElseThrow();
        assertThat(runForCopy.inputSourceVersionIds()).containsExactly(secondVersion.versionId());
        assertThat(cache.readManifest(tenantId, sourceVersion.contentRefId()).orElseThrow()
            .processingRunId()).isEqualTo(runForCopy.processingRunId());
    }

    @Test
    void changedSourceVersionProducesNewKnowledgeWithoutMutatingPriorDerivedState(@TempDir Path fixtureRoot)
            throws IOException {
        Path document = fixtureRoot.resolve("knowledge/changed.md");
        Files.createDirectories(document.getParent());
        Files.writeString(document, "first rendition of the knowledge source");
        FilesystemAdapter adapter = new FilesystemAdapter();
        ContentPullPort pullPort = new ContentPullPort(List.of(adapter));
        FileSystemContentStore contentStore = new FileSystemContentStore(
            fixtureRoot.resolveSibling(fixtureRoot.getFileName() + "-changed-cache"));
        AcquireStage acquire = new AcquireStage(pullPort, contentStore);
        ExtractionStage extraction = mock(ExtractionStage.class);
        PersistStage persist = new PersistStage(cache);
        PipelineStage<ParsedDocument, ChunkedDocument> chunk = new PipelineStage<>() {
            @Override
            public String name() { return "test-chunk"; }
            @Override
            public ChunkedDocument apply(ParsedDocument parsed, StageContext context) {
                var acquired = parsed.acquired();
                var knowledgeChunk = new SemanticChunk("k0", acquired.contentRefId().toString(), 0,
                    SemanticChunk.ChunkType.SECTION, new String(acquired.bytes(),
                        java.nio.charset.StandardCharsets.UTF_8), null, List.of("Section"), "Section",
                    List.of("element-1"), 1, 1, 5, false, Map.of(), SemanticChunk.PUBLIC_ONLY, "chunksha");
                return new ChunkedDocument(parsed, List.of(knowledgeChunk));
            }
        };
        PipelineStage<ChunkedDocument, ChunkedDocument> identityStage = new PipelineStage<>() {
            @Override
            public String name() { return "test-identity"; }
            @Override
            public ChunkedDocument apply(ChunkedDocument value, StageContext context) {
                return value;
            }
        };
        when(extraction.apply(any(), any())).thenAnswer(invocation -> {
            var acquired = invocation.<org.synanton.synflux.domain.AcquiredDocument>getArgument(0);
            return new ParsedDocument(acquired,
                new String(acquired.bytes(), java.nio.charset.StandardCharsets.UTF_8), Map.of(), null);
        });

        String tenantId = "tenant-knowledge-change-" + UUID.randomUUID();
        String sourceId = "changed-source-" + UUID.randomUUID();
        cache.createSourceIfAbsent(new SourceRow(
            tenantId, sourceId, "filesystem", "file", fixtureRoot.toUri().toString(),
            Set.of("DISCOVER"), "ACTIVE", Instant.parse("2026-10-17T11:59:00Z"), null
        ));
        OperationService operationService = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        FixtureSourceWorkflow workflow = new FixtureSourceWorkflow(
            cache, pullPort, acquire, extraction, chunk, identityStage, identityStage, identityStage,
            persist, testProperties(), lifecycle, operationService, Runnable::run,
            new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        VerifiedIngestionContext context = principal(tenantId);

        workflow.ingest(context, sourceId, "knowledge/changed.md", "request-first", "changed-key");
        SourceVersionRow firstVersion = cache.listSourceVersions(
            tenantId, "filesystem", sourceId, "knowledge/changed.md").getFirst();
        List<ChunkRow> firstKnowledge = cache.readChunks(tenantId, firstVersion.contentRefId());
        assertThat(firstKnowledge).isNotEmpty();
        UUID firstRunId = firstKnowledge.getFirst().processingRunId();

        Files.writeString(document, "second rendition with different canonical content");
        FixtureSourceWorkflow.Submission changed = workflow.ingest(
            context, sourceId, "knowledge/changed.md", "request-second", "changed-key-2");
        SourceVersionRow secondVersion = cache.listSourceVersions(
            tenantId, "filesystem", sourceId, "knowledge/changed.md").stream()
            .filter(version -> !version.versionId().equals(firstVersion.versionId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("changed source produced no new version: " + changed.run().status()));

        // Prior source history and derived state are untouched; new knowledge is new state.
        assertThat(cache.readSourceVersion(tenantId, "filesystem", sourceId, "knowledge/changed.md",
            firstVersion.versionId())).contains(firstVersion);
        assertThat(cache.readChunks(tenantId, firstVersion.contentRefId()))
            .containsExactlyInAnyOrderElementsOf(firstKnowledge);
        assertThat(cache.readChunks(tenantId, firstVersion.contentRefId()).getFirst().processingRunId())
            .isEqualTo(firstRunId);
        List<ChunkRow> secondKnowledge = cache.readChunks(tenantId, secondVersion.contentRefId());
        assertThat(secondKnowledge).isNotEmpty();
        assertThat(secondKnowledge).isNotEqualTo(firstKnowledge);
        for (ChunkRow knowledgeChunk : secondKnowledge) {
            assertThat(knowledgeChunk.sourceVersionIds()).containsExactly(secondVersion.versionId());
        }
        assertThat(secondKnowledge.getFirst().processingRunId()).isNotEqualTo(firstRunId);
        assertThat(cache.readKnowledgeProcessingRun(tenantId, secondKnowledge.getFirst().processingRunId())
            .orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(changed.run().status()).isEqualTo("COMPLETED");
    }

    @Test
    void restartAfterAcquireCheckpointRestoresCachedBytesWithoutRepeatingAcquire(@TempDir Path fixtureRoot)
            throws Exception {
        Path document = fixtureRoot.resolve("restart/recover.md");
        Files.createDirectories(document.getParent());
        Files.writeString(document, "restart-safe source content");
        ContentPullPort pullPort = new ContentPullPort(List.of(new FilesystemAdapter()));
        Path cacheRoot = fixtureRoot.resolveSibling(fixtureRoot.getFileName() + "-restart-cache");
        FileSystemContentStore firstContentStore = spy(new FileSystemContentStore(cacheRoot));
        FileSystemContentStore reopenedContentStore = spy(new FileSystemContentStore(cacheRoot));
        ExtractionStage extraction = mock(ExtractionStage.class);
        PersistStage persist = mock(PersistStage.class);
        when(extraction.apply(any(), any())).thenAnswer(invocation -> {
            var acquired = invocation.<org.synanton.synflux.domain.AcquiredDocument>getArgument(0);
            return new ParsedDocument(acquired, "recovered text", Map.of(), null);
        });
        when(persist.apply(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        PipelineStage<ParsedDocument, ChunkedDocument> chunk = new PipelineStage<>() {
            @Override
            public String name() { return "test-chunk"; }
            @Override
            public ChunkedDocument apply(ParsedDocument parsed, StageContext context) {
                return new ChunkedDocument(parsed, List.of());
            }
        };
        PipelineStage<ChunkedDocument, ChunkedDocument> identityStage = new PipelineStage<>() {
            @Override
            public String name() { return "test-identity"; }
            @Override
            public ChunkedDocument apply(ChunkedDocument value, StageContext context) {
                return value;
            }
        };

        String tenantId = "tenant-workflow-restart-" + UUID.randomUUID();
        String sourceId = "restart-source-" + UUID.randomUUID();
        cache.createSourceIfAbsent(new SourceRow(
            tenantId, sourceId, "filesystem", "file", fixtureRoot.toUri().toString(), Set.of("DISCOVER"),
            "ACTIVE", Instant.parse("2026-10-17T11:59:00Z"), null));
        OperationService operationService = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        VerifiedIngestionContext context = principal(tenantId);
        FixtureSourceWorkflow beforeRestart = new FixtureSourceWorkflow(
            cache, pullPort, new AcquireStage(pullPort, firstContentStore), extraction, chunk,
            identityStage, identityStage, identityStage, persist, testProperties(), lifecycle,
            operationService, Runnable::run, new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        beforeRestart.setWorkflowStepObserver(stepId -> {
            if ("ACQUIRE".equals(stepId)) {
                throw new AssertionError("injected process crash after durable acquire checkpoint");
            }
        });

        assertThatThrownBy(() -> beforeRestart.submit(
            context, sourceId, "restart/recover.md", "request-restart", "restart-key"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("injected process crash");
        WorkflowStateRow persistedWorkflow = cache.listWorkflowStates(tenantId).getFirst();
        assertThat(persistedWorkflow.state()).isEqualTo("RUNNING");
        assertThat(persistedWorkflow.workflowId()).isNotEqualTo(persistedWorkflow.operationId())
            .isNotEqualTo(persistedWorkflow.runId());
        assertThat(cache.readWorkflowStep(tenantId, persistedWorkflow.workflowId(), "ACQUIRE").orElseThrow()
            .status()).isEqualTo("COMPLETED");
        assertThat(cache.listSourceVersions(tenantId, "filesystem", sourceId, "restart/recover.md")).isEmpty();

        FixtureSourceWorkflow afterRestart = new FixtureSourceWorkflow(
            cache, pullPort, new AcquireStage(pullPort, reopenedContentStore), extraction, chunk,
            identityStage, identityStage, identityStage, persist, testProperties(), lifecycle,
            operationService, Runnable::run, new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(Instant.parse("2026-10-17T12:00:01Z"), ZoneOffset.UTC));
        afterRestart.recoverPending();

        WorkflowStateRow completedWorkflow = cache.readWorkflowState(tenantId, persistedWorkflow.workflowId())
            .orElseThrow();
        IngestionRunRow recoveredRun = cache.readIngestionRun(tenantId, persistedWorkflow.runId()).orElseThrow();
        OperationRow recoveredOperation = cache.readOperation(tenantId, persistedWorkflow.operationId()).orElseThrow();
        assertThat(completedWorkflow.state()).isEqualTo("COMPLETED");
        assertThat(recoveredRun.status()).isEqualTo("COMPLETED");
        assertThat(recoveredOperation.status()).isEqualTo("SUCCEEDED");
        assertThat(cache.listSourceVersions(tenantId, "filesystem", sourceId, "restart/recover.md"))
            .hasSize(1);
        var sourceEvent = cache.listSourceOutbox(tenantId, "filesystem", sourceId, "restart/recover.md")
            .getFirst();
        WorkflowStepRow ingestStep = cache.readWorkflowStep(
            tenantId, persistedWorkflow.workflowId(), "INGEST").orElseThrow();
        assertThat(sourceEvent.payloadJson())
            .contains("\"event_version\":1", "\"tenant_id\":\"" + tenantId + "\"",
                "\"correlation_id\":\"request-restart\"",
                "\"causation_id\":\"" + ingestStep.commandId() + "\"",
                "\"workflow_id\":\"" + persistedWorkflow.workflowId() + "\"",
                "\"operation_id\":\"" + persistedWorkflow.operationId() + "\"",
                "\"run_id\":\"" + persistedWorkflow.runId() + "\"");
        verify(firstContentStore, times(1)).put(eq(tenantId), any(byte[].class), any());
        verify(reopenedContentStore, never()).put(eq(tenantId), any(byte[].class), any());
        verify(extraction, times(1)).apply(any(), any());
        verify(persist, times(1)).apply(any(), any());
    }

    @Test
    void crashAfterIngestCommitFinalizesOnRecoveryWithoutRerunningIngest(@TempDir Path fixtureRoot)
            throws Exception {
        Path document = fixtureRoot.resolve("irreversible/commit.md");
        Files.createDirectories(document.getParent());
        Files.writeString(document, "irreversible boundary content");
        ContentPullPort pullPort = new ContentPullPort(List.of(new FilesystemAdapter()));
        FileSystemContentStore contentStore = new FileSystemContentStore(
            fixtureRoot.resolveSibling(fixtureRoot.getFileName() + "-irreversible-cache"));
        ExtractionStage extraction = mock(ExtractionStage.class);
        PersistStage persist = mock(PersistStage.class);
        when(extraction.apply(any(), any())).thenAnswer(invocation -> {
            var acquired = invocation.<org.synanton.synflux.domain.AcquiredDocument>getArgument(0);
            return new ParsedDocument(acquired, "committed text", Map.of(), null);
        });
        when(persist.apply(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        PipelineStage<ParsedDocument, ChunkedDocument> chunk = new PipelineStage<>() {
            @Override
            public String name() { return "test-chunk"; }
            @Override
            public ChunkedDocument apply(ParsedDocument parsed, StageContext context) {
                return new ChunkedDocument(parsed, List.of());
            }
        };
        PipelineStage<ChunkedDocument, ChunkedDocument> identityStage = new PipelineStage<>() {
            @Override
            public String name() { return "test-identity"; }
            @Override
            public ChunkedDocument apply(ChunkedDocument value, StageContext context) {
                return value;
            }
        };

        String tenantId = "tenant-irreversible-" + UUID.randomUUID();
        String sourceId = "irreversible-source-" + UUID.randomUUID();
        cache.createSourceIfAbsent(new SourceRow(
            tenantId, sourceId, "filesystem", "file", fixtureRoot.toUri().toString(), Set.of("DISCOVER"),
            "ACTIVE", Instant.parse("2026-10-17T11:59:00Z"), null));
        OperationService operationService = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        VerifiedIngestionContext context = principal(tenantId);
        FixtureSourceWorkflow crashingWorker = new FixtureSourceWorkflow(
            cache, pullPort, new AcquireStage(pullPort, contentStore), extraction, chunk,
            identityStage, identityStage, identityStage, persist, testProperties(), lifecycle,
            operationService, Runnable::run, new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        // The crash lands after the SourceVersion/outbox transaction committed and the
        // INGEST step checkpoint was recorded, but before the workflow row was finalized.
        crashingWorker.setWorkflowStepObserver(stepId -> {
            if ("INGEST".equals(stepId)) {
                throw new AssertionError("injected process crash after ingest commit");
            }
        });

        assertThatThrownBy(() -> crashingWorker.submit(
            context, sourceId, "irreversible/commit.md", "request-irreversible", "irreversible-key"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("injected process crash");
        WorkflowStateRow stuckWorkflow = cache.listWorkflowStates(tenantId).getFirst();
        IngestionRunRow committedRun = cache.readIngestionRun(tenantId, stuckWorkflow.runId()).orElseThrow();
        assertThat(committedRun.status()).isEqualTo("COMPLETED");
        assertThat(stuckWorkflow.state()).isEqualTo("RUNNING");
        assertThat(cache.readWorkflowStep(tenantId, stuckWorkflow.workflowId(), "INGEST").orElseThrow()
            .status()).isEqualTo("COMPLETED");
        assertThat(cache.listSourceVersions(tenantId, "filesystem", sourceId, "irreversible/commit.md"))
            .hasSize(1);

        FixtureSourceWorkflow recoveringWorker = new FixtureSourceWorkflow(
            cache, pullPort, new AcquireStage(pullPort, contentStore), extraction, chunk,
            identityStage, identityStage, identityStage, persist, testProperties(), lifecycle,
            operationService, Runnable::run, new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(Instant.parse("2026-10-17T12:00:01Z"), ZoneOffset.UTC));
        recoveringWorker.recoverPending();

        WorkflowStateRow finalizedWorkflow = cache.readWorkflowState(tenantId, stuckWorkflow.workflowId())
            .orElseThrow();
        IngestionRunRow finalizedRun = cache.readIngestionRun(tenantId, stuckWorkflow.runId()).orElseThrow();
        OperationRow finalizedOperation = cache.readOperation(tenantId, stuckWorkflow.operationId()).orElseThrow();
        assertThat(finalizedWorkflow.state()).isEqualTo("COMPLETED");
        assertThat(finalizedRun.status()).isEqualTo("COMPLETED");
        assertThat(finalizedOperation.status()).isEqualTo("SUCCEEDED");
        assertThat(cache.listSourceVersions(tenantId, "filesystem", sourceId, "irreversible/commit.md"))
            .hasSize(1);
        assertThat(cache.listSourceOutbox(tenantId, "filesystem", sourceId, "irreversible/commit.md"))
            .hasSize(1);
        // IRREVERSIBLE: the ingest side effects are never re-executed after the commit.
        verify(extraction, times(1)).apply(any(), any());
        verify(persist, times(1)).apply(any(), any());
    }

    @Test
    void expiredLeaseIsTakenOverAndExactlyOneWorkerCompletes(@TempDir Path fixtureRoot)
            throws Exception {
        Path document = fixtureRoot.resolve("lease/takeover.md");
        Files.createDirectories(document.getParent());
        Files.writeString(document, "lease takeover content");
        ContentPullPort pullPort = new ContentPullPort(List.of(new FilesystemAdapter()));
        FileSystemContentStore contentStore = new FileSystemContentStore(
            fixtureRoot.resolveSibling(fixtureRoot.getFileName() + "-lease-cache"));
        ExtractionStage extraction = mock(ExtractionStage.class);
        PersistStage persist = mock(PersistStage.class);
        when(extraction.apply(any(), any())).thenAnswer(invocation -> {
            var acquired = invocation.<org.synanton.synflux.domain.AcquiredDocument>getArgument(0);
            return new ParsedDocument(acquired, "takeover text", Map.of(), null);
        });
        when(persist.apply(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        PipelineStage<ParsedDocument, ChunkedDocument> chunk = new PipelineStage<>() {
            @Override
            public String name() { return "test-chunk"; }
            @Override
            public ChunkedDocument apply(ParsedDocument parsed, StageContext context) {
                return new ChunkedDocument(parsed, List.of());
            }
        };
        PipelineStage<ChunkedDocument, ChunkedDocument> identityStage = new PipelineStage<>() {
            @Override
            public String name() { return "test-identity"; }
            @Override
            public ChunkedDocument apply(ChunkedDocument value, StageContext context) {
                return value;
            }
        };

        String tenantId = "tenant-lease-" + UUID.randomUUID();
        String sourceId = "lease-source-" + UUID.randomUUID();
        cache.createSourceIfAbsent(new SourceRow(
            tenantId, sourceId, "filesystem", "file", fixtureRoot.toUri().toString(), Set.of("DISCOVER"),
            "ACTIVE", Instant.parse("2026-10-17T11:59:00Z"), null));
        OperationService operationService = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        VerifiedIngestionContext context = principal(tenantId);
        FixtureSourceWorkflow crashingWorker = new FixtureSourceWorkflow(
            cache, pullPort, new AcquireStage(pullPort, contentStore), extraction, chunk,
            identityStage, identityStage, identityStage, persist, testProperties(), lifecycle,
            operationService, Runnable::run, new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        crashingWorker.setWorkflowStepObserver(stepId -> {
            if ("ACQUIRE".equals(stepId)) {
                throw new AssertionError("injected worker A crash holding the lease");
            }
        });
        assertThatThrownBy(() -> crashingWorker.submit(
            context, sourceId, "lease/takeover.md", "request-lease", "lease-key"))
            .isInstanceOf(AssertionError.class);
        WorkflowStateRow orphanedWorkflow = cache.listWorkflowStates(tenantId).getFirst();

        // Worker A died holding the lease: the row exists, unexpired, owned by A.
        UUID ownerA = UUID.randomUUID();
        Instant leaseStart = Instant.parse("2026-10-17T12:00:00Z");
        assertThat(cache.claimWorkflowLease(tenantId, orphanedWorkflow.workflowId(), ownerA,
            leaseStart, leaseStart.plusSeconds(30))).isTrue();
        // A different owner cannot claim while the lease is live.
        assertThat(cache.claimWorkflowLease(tenantId, orphanedWorkflow.workflowId(), UUID.randomUUID(),
            leaseStart.plusSeconds(5), leaseStart.plusSeconds(35))).isFalse();

        // Worker B recovers while A's lease is live: skipped, no work executed.
        FixtureSourceWorkflow liveLeaseWorker = new FixtureSourceWorkflow(
            cache, pullPort, new AcquireStage(pullPort, contentStore), extraction, chunk,
            identityStage, identityStage, identityStage, persist, testProperties(), lifecycle,
            operationService, Runnable::run, new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(leaseStart.plusSeconds(5), ZoneOffset.UTC));
        liveLeaseWorker.recoverPending();
        assertThat(cache.readIngestionRun(tenantId, orphanedWorkflow.runId()).orElseThrow().completedAt())
            .isNull();
        verify(extraction, never()).apply(any(), any());

        // After expiry, worker C takes over and the same operation completes exactly once.
        FixtureSourceWorkflow takeoverWorker = new FixtureSourceWorkflow(
            cache, pullPort, new AcquireStage(pullPort, contentStore), extraction, chunk,
            identityStage, identityStage, identityStage, persist, testProperties(), lifecycle,
            operationService, Runnable::run, new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(leaseStart.plusSeconds(45), ZoneOffset.UTC));
        takeoverWorker.recoverPending();

        WorkflowStateRow completedWorkflow = cache.readWorkflowState(tenantId, orphanedWorkflow.workflowId())
            .orElseThrow();
        IngestionRunRow completedRun = cache.readIngestionRun(tenantId, orphanedWorkflow.runId()).orElseThrow();
        OperationRow completedOperation = cache.readOperation(tenantId, orphanedWorkflow.operationId()).orElseThrow();
        assertThat(completedWorkflow.state()).isEqualTo("COMPLETED");
        assertThat(completedRun.status()).isEqualTo("COMPLETED");
        assertThat(completedOperation.status()).isEqualTo("SUCCEEDED");
        assertThat(cache.listSourceVersions(tenantId, "filesystem", sourceId, "lease/takeover.md"))
            .hasSize(1);
        assertThat(cache.listSourceOutbox(tenantId, "filesystem", sourceId, "lease/takeover.md"))
            .hasSize(1);
        verify(extraction, times(1)).apply(any(), any());
        verify(persist, times(1)).apply(any(), any());
    }

    @Test
    void sourceOutboxRetryMetadataAndDeadLetterRetainStableEventIdentity() {
        VerifiedIngestionContext context = principal("tenant-source-outbox-retry-" + UUID.randomUUID());
        byte[] bytes = "outbox source event".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        IngestionRunRow run = lifecycle.ingest(
            context, UUID.randomUUID(), UUID.randomUUID(), "file", "outbox-source", "guide.md",
            fileRef("file:///outbox/guide.md", null, bytes.length), bytes,
            testReference(context.tenantId()), (ignored, versionId) -> new FixtureIngestionService.PublishedContent(UUID.randomUUID()));
        SourceOutboxRow original = cache.listSourceOutbox(
            context.tenantId(), "file", "outbox-source", "guide.md").getFirst();
        Instant failedAt = Instant.parse("2026-10-17T12:01:00Z");

        cache.recordSourceOutboxFailure(original, 1, "broker timeout", failedAt.plusSeconds(1), false, failedAt);
        SourceOutboxRow retry = cache.listSourceOutbox(
            context.tenantId(), "file", "outbox-source", "guide.md").getFirst();
        assertThat(retry.eventId()).isEqualTo(original.eventId());
        assertThat(retry.attemptCount()).isEqualTo(1);
        assertThat(retry.lastError()).isEqualTo("broker timeout");
        assertThat(retry.nextAttemptAt()).isEqualTo(failedAt.plusSeconds(1));
        assertThat(retry.deadLettered()).isFalse();

        cache.recordSourceOutboxFailure(retry, 5, "retry limit reached", null, true, failedAt.plusSeconds(10));
        SourceOutboxRow deadLettered = cache.listSourceOutbox(
            context.tenantId(), "file", "outbox-source", "guide.md").getFirst();
        assertThat(deadLettered.eventId()).isEqualTo(original.eventId());
        assertThat(deadLettered.deadLettered()).isTrue();
        assertThat(cache.listUnpublishedSourceOutbox(context.tenantId(), 100, failedAt.plusSeconds(20)))
            .isEmpty();
        var deadLetter = session.execute(SimpleStatement.newInstance(
            "SELECT event_id, attempt_count, last_error FROM ingestion_cache.dead_letter_events " +
                "WHERE tenant_id=? AND event_id=?", context.tenantId(), original.eventId())).one();
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.getUuid("event_id")).isEqualTo(original.eventId());
        assertThat(deadLetter.getInt("attempt_count")).isEqualTo(5);
        assertThat(deadLetter.getString("last_error")).isEqualTo("retry limit reached");
        assertThat(run.status()).isEqualTo("COMPLETED");

        SourceVersionRow sourceVersion = cache.listSourceVersions(
            context.tenantId(), "file", "outbox-source", "guide.md").getFirst();
        assertThat(cache.replaySourceOutbox(deadLettered)).isTrue();
        SourceOutboxRow replay = cache.listUnpublishedSourceOutbox(
            context.tenantId(), 100, failedAt.plusSeconds(20)).getFirst();
        assertThat(replay.eventId()).isEqualTo(deadLettered.eventId());
        assertThat(replay.payloadJson()).isEqualTo(deadLettered.payloadJson());
        assertThat(replay.attemptCount()).isZero();
        assertThat(cache.readSourceVersion(context.tenantId(), "file", "outbox-source", "guide.md",
            sourceVersion.versionId())).contains(sourceVersion);
    }

    @Test
    void retryableAcquireFailureKeepsOperationRunningAndRecoversAtPersistedDeadline(@TempDir Path fixtureRoot)
            throws IOException {
        Path document = fixtureRoot.resolve("retry/recover.md");
        Files.createDirectories(document.getParent());
        byte[] bytes = "retryable source content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(document, bytes);
        ContentPullPort pullPort = new ContentPullPort(List.of(new FilesystemAdapter()));
        AcquireStage acquire = mock(AcquireStage.class);
        AtomicInteger acquireAttempts = new AtomicInteger();
        when(acquire.apply(any(), any())).thenAnswer(invocation -> {
            if (acquireAttempts.incrementAndGet() == 1) {
                throw new ContentStoreException("temporary Content Cache outage");
            }
            ContentRef ref = invocation.getArgument(0);
            return new org.synanton.synflux.domain.AcquiredDocument(
                ref, bytes, "digest", ref.mimeType(), ref.uri(), UUID.randomUUID(), "cache-reference");
        });
        ExtractionStage extraction = mock(ExtractionStage.class);
        PersistStage persist = mock(PersistStage.class);
        when(extraction.apply(any(), any())).thenAnswer(invocation -> {
            var acquired = invocation.<org.synanton.synflux.domain.AcquiredDocument>getArgument(0);
            return new ParsedDocument(acquired, "recovered text", Map.of(), null);
        });
        when(persist.apply(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        PipelineStage<ParsedDocument, ChunkedDocument> chunk = new PipelineStage<>() {
            @Override
            public String name() { return "test-chunk"; }
            @Override
            public ChunkedDocument apply(ParsedDocument parsed, StageContext context) {
                return new ChunkedDocument(parsed, List.of());
            }
        };
        PipelineStage<ChunkedDocument, ChunkedDocument> identityStage = new PipelineStage<>() {
            @Override
            public String name() { return "test-identity"; }
            @Override
            public ChunkedDocument apply(ChunkedDocument value, StageContext context) {
                return value;
            }
        };

        String tenantId = "tenant-workflow-retry-" + UUID.randomUUID();
        String sourceId = "retry-source-" + UUID.randomUUID();
        cache.createSourceIfAbsent(new SourceRow(
            tenantId, sourceId, "filesystem", "file", fixtureRoot.toUri().toString(), Set.of("DISCOVER"),
            "ACTIVE", Instant.parse("2026-10-17T11:59:00Z"), null));
        Instant baseTime = Instant.parse("2026-10-17T12:00:00Z");
        OperationService operationService = new OperationService(cache, Clock.fixed(baseTime, ZoneOffset.UTC));
        FixtureSourceWorkflow firstAttempt = new FixtureSourceWorkflow(
            cache, pullPort, acquire, extraction, chunk, identityStage, identityStage, identityStage,
            persist, testProperties(), lifecycle, operationService, Runnable::run,
            new FilesystemFixturePolicy(fixtureRoot), Clock.fixed(baseTime, ZoneOffset.UTC));

        FixtureSourceWorkflow.Submission accepted = firstAttempt.submit(
            principal(tenantId), sourceId, "retry/recover.md", "request-retry", "retry-key");
        WorkflowStateRow retryingWorkflow = cache.listWorkflowStates(tenantId).getFirst();
        IngestionRunRow retryingRun = cache.readIngestionRun(tenantId, accepted.run().runId()).orElseThrow();
        OperationRow runningOperation = cache.readOperation(tenantId, accepted.operation().operationId()).orElseThrow();
        WorkflowStepRow failedAcquireStep = cache.readWorkflowStep(
            tenantId, retryingWorkflow.workflowId(), "ACQUIRE").orElseThrow();
        assertThat(retryingWorkflow.state()).isEqualTo("RETRYABLE");
        assertThat(retryingWorkflow.nextAttemptAt()).isEqualTo(baseTime.plusSeconds(1));
        assertThat(retryingRun.completedAt()).isNull();
        assertThat(runningOperation.status()).isEqualTo("RUNNING");
        assertThat(failedAcquireStep.commandId()).isEqualTo(UUID.nameUUIDFromBytes(
            ("synflux-workflow-command:" + retryingWorkflow.workflowId() + ":ACQUIRE")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var firstError = cache.readIngestionError(
            tenantId, UUID.fromString(retryingRun.errorRef())).orElseThrow();
        assertThat(firstError.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(firstError.retryable()).isTrue();

        FixtureSourceWorkflow restarted = new FixtureSourceWorkflow(
            cache, pullPort, acquire, extraction, chunk, identityStage, identityStage, identityStage,
            persist, testProperties(), lifecycle, operationService, Runnable::run,
            new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(baseTime.plusSeconds(5), ZoneOffset.UTC));
        restarted.recoverPending();

        IngestionRunRow completedRun = cache.readIngestionRun(tenantId, accepted.run().runId()).orElseThrow();
        OperationRow completedOperation = cache.readOperation(
            tenantId, accepted.operation().operationId()).orElseThrow();
        WorkflowStepRow acquireStep = cache.readWorkflowStep(
            tenantId, retryingWorkflow.workflowId(), "ACQUIRE").orElseThrow();
        assertThat(completedRun.status()).isEqualTo("COMPLETED");
        assertThat(completedOperation.status()).isEqualTo("SUCCEEDED");
        assertThat(cache.readWorkflowState(tenantId, retryingWorkflow.workflowId()).orElseThrow().state())
            .isEqualTo("COMPLETED");
        assertThat(acquireStep.status()).isEqualTo("COMPLETED");
        assertThat(acquireStep.attemptCount()).isEqualTo(2);
        assertThat(acquireStep.commandId()).isEqualTo(failedAcquireStep.commandId());
        assertThat(cache.listSourceVersions(tenantId, "filesystem", sourceId, "retry/recover.md"))
            .hasSize(1);
    }

    @Test
    void runningOperationExposesCancelRequestedThenCompletesAsCancelled(@TempDir Path fixtureRoot) throws Exception {
        Path document = fixtureRoot.resolve("cancel/cancellation.md");
        Files.createDirectories(document.getParent());
        Files.writeString(document, "cancel during extraction");
        FilesystemAdapter adapter = new FilesystemAdapter();
        ContentPullPort pullPort = new ContentPullPort(List.of(adapter));
        FileSystemContentStore contentStore = new FileSystemContentStore(
            fixtureRoot.resolveSibling(fixtureRoot.getFileName() + "-cancel-cache"));
        ExtractionStage extraction = mock(ExtractionStage.class);
        PersistStage persist = mock(PersistStage.class);
        CountDownLatch extractionStarted = new CountDownLatch(1);
        CountDownLatch finishExtraction = new CountDownLatch(1);
        when(extraction.apply(any(), any())).thenAnswer(invocation -> {
            extractionStarted.countDown();
            if (!finishExtraction.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test extraction release timed out");
            }
            var acquired = invocation.<org.synanton.synflux.domain.AcquiredDocument>getArgument(0);
            return new ParsedDocument(acquired, "extracted", Map.of(), null);
        });
        when(persist.apply(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        PipelineStage<ParsedDocument, ChunkedDocument> chunk = new PipelineStage<>() {
            @Override
            public String name() { return "test-chunk"; }
            @Override
            public ChunkedDocument apply(ParsedDocument parsed, StageContext context) {
                return new ChunkedDocument(parsed, List.of());
            }
        };
        PipelineStage<ChunkedDocument, ChunkedDocument> identityStage = new PipelineStage<>() {
            @Override
            public String name() { return "test-identity"; }
            @Override
            public ChunkedDocument apply(ChunkedDocument document, StageContext context) {
                return document;
            }
        };

        String tenantId = "tenant-cancel-workflow-" + UUID.randomUUID();
        String sourceId = "cancel-source-" + UUID.randomUUID();
        cache.createSourceIfAbsent(new SourceRow(
            tenantId, sourceId, "filesystem", "file", fixtureRoot.toUri().toString(), Set.of(), "ACTIVE",
            Instant.parse("2026-10-17T11:59:00Z"), null
        ));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OperationService operationService = new OperationService(cache,
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        FixtureSourceWorkflow workflow = new FixtureSourceWorkflow(
            cache, pullPort, new AcquireStage(pullPort, contentStore), extraction, chunk, identityStage, identityStage,
            identityStage, persist, testProperties(), lifecycle, operationService, executor,
            new FilesystemFixturePolicy(fixtureRoot),
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        VerifiedIngestionContext context = principal(tenantId);

        try {
            FixtureSourceWorkflow.Submission submitted = workflow.submit(
                context, sourceId, "cancel/cancellation.md", "request-cancel", "cancel-key");
            assertThat(submitted.operation().status()).isEqualTo("RUNNING");
            assertThat(extractionStarted.await(10, TimeUnit.SECONDS)).isTrue();

            OperationRow requested = workflow.cancelOperation(context, submitted.operation().operationId());
            assertThat(requested.status()).isEqualTo("CANCEL_REQUESTED");
            finishExtraction.countDown();

            OperationRow terminal = null;
            for (int attempt = 0; attempt < 100; attempt++) {
                terminal = cache.readOperation(tenantId, submitted.operation().operationId()).orElseThrow();
                if ("CANCELLED".equals(terminal.status())) {
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(terminal).isNotNull();
            assertThat(terminal.status()).isEqualTo("CANCELLED");
            IngestionRunRow run = cache.readIngestionRun(tenantId, submitted.run().runId()).orElseThrow();
            assertThat(run.status()).isEqualTo("CANCELLED");
            assertThat(run.result().cancelled()).isEqualTo(1);
            assertThat(cache.listSourceVersions(tenantId, "filesystem", sourceId, "cancel/cancellation.md"))
                .isEmpty();
        } finally {
            finishExtraction.countDown();
            executor.shutdownNow();
        }
    }

    private static IngestionRunRow ingest(
            VerifiedIngestionContext context,
            String sourceId,
            String resourceId,
            ContentRef ref,
            byte[] bytes,
            FixtureIngestionService.ContentPublisher publisher) {
        return lifecycle.ingest(context, UUID.randomUUID(), UUID.randomUUID(), ref.scheme(), sourceId, resourceId,
            ref, bytes, testReference(context.tenantId()), publisher);
    }

    /** Well-formed opaque reference in the v1 wire format the Content Cache port returns. */
    private static String testReference(String tenantId) {
        return new ContentReference(tenantId, UUID.randomUUID().toString(),
            "sha256:" + UUID.randomUUID(), 1, 1).encode();
    }

    private static List<SourceVersionRow> versions(
            VerifiedIngestionContext context, String sourceId, String resourceId) {
        return cache.listSourceVersions(context.tenantId(), "file", sourceId, resourceId);
    }

    private static VerifiedIngestionContext principal(String tenantId) {
        return new VerifiedIngestionContext(tenantId, "fixture-connector", "CONNECTOR");
    }

    private static ContentRef fileRef(String uri, Instant lastModified, long size) {
        return new ContentRef("file", uri, "text/markdown", size, lastModified);
    }

    private static SynfluxProperties testProperties() {
        return new SynfluxProperties(
            new SynfluxProperties.Ingest(1, 1000,
                new SynfluxProperties.Ingest.Chunk("semantic-v1", 400, 50)),
            new SynfluxProperties.Pipeline(false, false, false),
            new SynfluxProperties.Enrichment("http://x", "m", 1),
            new SynfluxProperties.Embedding("http://x", "m", 1),
            new SynfluxProperties.Annotation("http://x", "rule-engine", "1.0"),
            new SynfluxProperties.Kafka(1, 1, 1)
        );
    }
}
