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
import org.synanton.contentcache.ContentReference;
import org.synanton.contentcache.FileSystemContentStore;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.IngestionRunRow;
import org.synanton.ingestioncache.domain.OperationRow;
import org.synanton.ingestioncache.domain.SourceIdentityConflictException;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.synflux.api.SourceController;
import org.synanton.synflux.config.SynfluxProperties;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
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
import static org.mockito.Mockito.mock;
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
            ignored -> {
                publications.incrementAndGet();
                return UUID.randomUUID();
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
            ignored -> {
                publications.incrementAndGet();
                return UUID.randomUUID();
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
            ignored -> UUID.randomUUID());
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
            bytes, ignored -> sharedContentRefId);
        ingest(context, "fixture-corpus", "b/guide.md", fileRef("file:///b/guide.md", modified, bytes.length),
            bytes, ignored -> sharedContentRefId);

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

        ingest(firstTenant, "fixture-corpus", "guide.md", ref, bytes, ignored -> sharedContentRefId);
        ingest(secondTenant, "fixture-corpus", "guide.md", ref, bytes, ignored -> sharedContentRefId);

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
            ignored -> UUID.randomUUID());

        IngestionRunRow conflict = ingest(context, sourceId, resourceId, ref,
            "two?".getBytes(java.nio.charset.StandardCharsets.UTF_8), ignored -> UUID.randomUUID());
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
            bytes, ignored -> UUID.randomUUID());

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
            context, UUID.randomUUID(), UUID.randomUUID(), sourceId, resourceId,
            fileRef("file:///legal/policy.md", modified, bytes.length), bytes, ignored -> UUID.randomUUID());
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
        IngestionRunRow failed = lifecycle.ingest(context, UUID.randomUUID(), operationId, sourceId, resourceId,
            ref, bytes, ignored -> { throw new IOException("fixture publish outage"); });
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.result().failed()).isEqualTo(1);
        var error = cache.readIngestionError(context.tenantId(), UUID.fromString(failed.errorRef())).orElseThrow();
        assertThat(error.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(error.status()).isEqualTo(503);
        assertThat(error.retryable()).isTrue();

        IngestionRunRow retried = lifecycle.ingest(context, UUID.randomUUID(), operationId, sourceId, resourceId,
            ref, bytes, ignored -> UUID.randomUUID());
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
            new FilesystemFixturePolicy(fixtureRoot));

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
    void cancellationRecordsCancelledDispositionWithoutPublishingVersion() {
        VerifiedIngestionContext context = principal("tenant-cancelled-run");
        byte[] bytes = "cancel me".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        IngestionRunRow cancelled = lifecycle.ingest(
            context, UUID.randomUUID(), UUID.randomUUID(), "fixture", "cancel/readme.md",
            fileRef("file:///cancel/readme.md", Instant.parse("2026-10-15T10:00:00Z"), bytes.length),
            bytes, ignored -> { throw new CancellationException("cancel requested"); });

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

        IngestionRunRow run = lifecycle.ingest(context, UUID.randomUUID(), UUID.randomUUID(), "local-fixture",
            resourceId, ref, acquiredBytes, ignored -> UUID.randomUUID());

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
            new FilesystemFixturePolicy(fixtureRoot));
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
            operationService, workerExecutor, new FilesystemFixturePolicy(fixtureRoot));
        VerifiedIngestionContext context = principal(tenantId);

        try {
            FixtureSourceWorkflow.Submission first = workflow.submit(
                context, sourceId, "manuals/concurrent.md", "request-first", "concurrent-key");
            assertThat(extractionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            FixtureSourceWorkflow.Submission retry = workflow.submit(
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
            new FilesystemFixturePolicy(fixtureRoot));
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
        return lifecycle.ingest(context, UUID.randomUUID(), UUID.randomUUID(), sourceId, resourceId,
            ref, bytes, publisher);
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
