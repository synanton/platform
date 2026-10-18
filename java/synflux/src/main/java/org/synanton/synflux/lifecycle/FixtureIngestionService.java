package org.synanton.synflux.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.contentcache.ContentIntegrityException;
import org.synanton.contentcache.ContentStoreException;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.IngestionErrorRow;
import org.synanton.ingestioncache.domain.IngestionManifestRow;
import org.synanton.ingestioncache.domain.IngestionManifestEntryRow;
import org.synanton.ingestioncache.domain.IngestionRunRow;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceIdentityConflictException;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.synanton.ingestioncache.domain.SourceTombstoneRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.ingestioncache.domain.SyncResultRow;
import org.synanton.synvault.domain.ContentRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.UUID;

/**
 * Internal deterministic lifecycle harness for the filesystem fixture connector.
 * The caller supplies IDs from its trusted request/Operation boundary and publishes
 * content before this service commits a SourceVersion. It is intentionally not
 * exposed through the legacy /ingest endpoint, whose tenant is not trusted.
 */
public final class FixtureIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FixtureIngestionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID VERSION_NAMESPACE = UUID.fromString("7cb4c7de-1670-5b0a-9e95-2be8499ea851");
    private static final UUID TOMBSTONE_NAMESPACE = UUID.fromString("3e3d365b-1e72-5a78-beb9-634dd27a20b2");

    private final IngestionCacheClient cache;
    private final Clock clock;

    public FixtureIngestionService(IngestionCacheClient cache, Clock clock) {
        this.cache = Objects.requireNonNull(cache);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Records one fixture resource. The publisher should run the normal content/cache
     * pipeline and return its content-addressed pointer; only then is the source
     * version made authoritative.
     */
    public IngestionRunRow ingest(
            VerifiedIngestionContext context,
            UUID runId,
            UUID operationId,
            String sourceId,
            String resourceId,
            ContentRef ref,
            byte[] bytes,
            ContentPublisher publisher) {
        return ingest(context, runId, operationId, ref.scheme(), sourceId, resourceId, ref, bytes, "", publisher);
    }

    public IngestionRunRow ingest(
            VerifiedIngestionContext context,
            UUID runId,
            UUID operationId,
            String connectorId,
            String sourceId,
            String resourceId,
            ContentRef ref,
            byte[] bytes,
            ContentPublisher publisher) {
        return ingest(context, runId, operationId, connectorId, sourceId, resourceId, ref, bytes, "", publisher);
    }

    public IngestionRunRow ingest(
            VerifiedIngestionContext context,
            UUID runId,
            UUID operationId,
            String connectorId,
            String sourceId,
            String resourceId,
            ContentRef ref,
            byte[] bytes,
            String contentCacheReference,
            ContentPublisher publisher) {
        return ingest(context, runId, operationId, connectorId, sourceId, resourceId, ref, bytes,
            contentCacheReference, workflowId(operationId), operationId.toString(), operationId.toString(), false, publisher);
    }

    public IngestionRunRow ingest(
            VerifiedIngestionContext context,
            UUID runId,
            UUID operationId,
            String connectorId,
            String sourceId,
            String resourceId,
            ContentRef ref,
            byte[] bytes,
            String contentCacheReference,
            String workflowId,
            String correlationId,
            String causationId,
            boolean deferRetryableFailure,
            ContentPublisher publisher) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(ref);
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(publisher);
        requireText(connectorId, "connectorId");
        requireText(sourceId, "sourceId");
        validateResourceId(resourceId);
        validateFixtureConnector(ref.scheme());

        String tenantId = context.tenantId();
        var duplicateRun = existingRun(tenantId, runId, operationId, sourceId);
        if (duplicateRun.isPresent()) {
            return duplicateRun.get();
        }
        Instant startedAt = clock.instant();
        cache.upsertIngestionRun(new IngestionRunRow(
            tenantId, runId, operationId, sourceId, "ACQUIRING", startedAt, null, null, null
        ));

        try {
            ensureIdentity(context, connectorId, sourceId, resourceId, startedAt);
            String digest = sha256Hex(bytes);
            String sourceRevision = sourceRevision(ref, bytes.length, digest);
            UUID incarnationId = cache.readLatestSourceTombstone(tenantId, connectorId, sourceId, resourceId)
                .map(SourceTombstoneRow::nextIncarnationId)
                .orElse(null);
            UUID versionId = sourceVersionId(
                tenantId, connectorId, sourceId, resourceId, incarnationId, sourceRevision
            );
            Instant observedAt = clock.instant();

            cache.upsertManifestEntry(manifestEntry(
                tenantId, runId, connectorId, sourceId, resourceId, "DISCOVERED", null, null, observedAt
            ));

            var existing = cache.readSourceVersion(tenantId, connectorId, sourceId, resourceId, versionId);
            if (existing.isPresent()) {
                ensureSameRevision(existing.get(), digest, sourceRevision);
                cache.markSourceSyncSucceeded(tenantId, sourceId, observedAt);
                return finishRun(tenantId, runId, operationId, sourceId, startedAt, "UNCHANGED",
                    new SyncResultRow(0, 0, 1, 0, 0, 0, 0, 0), null,
                    manifestEntry(tenantId, runId, connectorId, sourceId, resourceId,
                        "UNCHANGED", versionId, null, observedAt));
            }

            // Every published SourceVersion must carry a resolvable opaque Content Cache
            // reference. A blank reference means provenance cannot be resolved, so the
            // artifact is withheld (QUARANTINED) — never published unverifiable.
            if (contentCacheReference == null || contentCacheReference.isBlank()) {
                throw new ContentIntegrityException(
                    "SourceVersion cannot be published without a Content Cache reference");
            }

            UUID contentRefId;
            try {
                contentRefId = publisher.publish(bytes, versionId);
            } catch (Exception publicationFailure) {
                if (publicationFailure instanceof CancellationException cancellation) {
                    throw cancellation;
                }
                throw new ContentPublicationException(publicationFailure);
            }
            SourceVersionRow version = new SourceVersionRow(
                tenantId, connectorId, sourceId, resourceId, versionId, digest, sourceRevision,
                observedAt, Objects.requireNonNull(contentRefId, "publisher contentRefId"), incarnationId,
                contentCacheReference == null ? "" : contentCacheReference
            );
            boolean inserted = cache.insertSourceVersionAndOutboxIfAbsent(
                version, sourcePublishedEvent(version, runId, operationId, workflowId, correlationId, causationId));
            if (!inserted) {
                SourceVersionRow concurrent = cache.readSourceVersion(
                    tenantId, connectorId, sourceId, resourceId, versionId
                ).orElseThrow(() -> new IllegalStateException("SourceVersion insert lost without a readable row"));
                ensureSameRevision(concurrent, digest, sourceRevision);
            }
            cache.markSourceSyncSucceeded(tenantId, sourceId, observedAt);
            return finishRun(tenantId, runId, operationId, sourceId, startedAt,
                inserted ? "COMPLETED" : "UNCHANGED",
                inserted ? new SyncResultRow(0, 1, 0, 0, 0, 0, 0, 0)
                    : new SyncResultRow(0, 0, 1, 0, 0, 0, 0, 0), null,
                manifestEntry(tenantId, runId, connectorId, sourceId, resourceId,
                    inserted ? "CHANGED" : "UNCHANGED", versionId, null, observedAt));
        } catch (Exception failure) {
            log.warn("Fixture ingestion run {} failed", runId, failure);
            Instant completedAt = clock.instant();
            // A lease-less worker must leave the run non-terminal: the new leaseholder
            // resumes it. Recording CANCELLED/FAILED here would fork the workflow.
            if (failure instanceof WorkflowLeaseLostException) {
                throw failure;
            }
            if (failure instanceof CancellationException) {
                return finishRun(tenantId, runId, operationId, sourceId, startedAt, "CANCELLED",
                    new SyncResultRow(0, 0, 0, 0, 0, 0, 0, 1), null,
                    manifestEntry(tenantId, runId, connectorId, sourceId, resourceId,
                        "CANCELLED", null, null, completedAt));
            }
            if (failure instanceof ContentIntegrityException) {
                String errorRef = persistQuarantineFailure(tenantId, operationId, runId,
                    "Acquired content could not be verified against a Content Cache reference",
                    completedAt);
                return finishRun(tenantId, runId, operationId, sourceId, startedAt, "QUARANTINED",
                    new SyncResultRow(0, 0, 0, 0, 0, 0, 1, 0), errorRef,
                    manifestEntry(tenantId, runId, connectorId, sourceId, resourceId,
                        "QUARANTINED", null, errorRef, completedAt));
            }
            if (deferRetryableFailure && retryable(failure)) {
                String errorRef = persistFailure(tenantId, operationId, runId, failure, completedAt);
                IngestionRunRow retrying = new IngestionRunRow(
                    tenantId, runId, operationId, sourceId, "ACQUIRING", startedAt, null, null, errorRef
                );
                cache.upsertIngestionRun(retrying);
                return retrying;
            }
            String errorRef = persistFailure(tenantId, operationId, runId, failure, completedAt);
            IngestionManifestEntryRow entry = manifestEntry(
                tenantId, runId, connectorId, sourceId, resourceId, "FAILED", null, errorRef, completedAt
            );
            return finishRun(tenantId, runId, operationId, sourceId, startedAt, "FAILED",
                new SyncResultRow(0, 0, 0, 0, 1, 0, 0, 0), errorRef, entry);
        }
    }

    /**
     * Records an authoritative deletion. Non-authoritative observations are retained
     * as SKIPPED manifest entries and never create tombstones.
     */
    public IngestionRunRow recordDeletion(
            VerifiedIngestionContext context,
            UUID runId,
            UUID operationId,
            String connectorId,
            String sourceId,
            String resourceId,
            DeletionSignal signal) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(signal);
        requireText(connectorId, "connectorId");
        requireText(sourceId, "sourceId");
        validateResourceId(resourceId);

        String tenantId = context.tenantId();
        var duplicateRun = existingRun(tenantId, runId, operationId, sourceId);
        if (duplicateRun.isPresent()) {
            return duplicateRun.get();
        }
        Instant startedAt = clock.instant();
        cache.upsertIngestionRun(new IngestionRunRow(
            tenantId, runId, operationId, sourceId, "VALIDATING", startedAt, null, null, null
        ));
        try {
            ensureIdentity(context, connectorId, sourceId, resourceId, startedAt);
            cache.upsertManifestEntry(manifestEntry(
                tenantId, runId, connectorId, sourceId, resourceId, "DISCOVERED", null, null, startedAt
            ));

            if (signal != DeletionSignal.DELETED) {
                return finishRun(tenantId, runId, operationId, sourceId, startedAt, "COMPLETED",
                    new SyncResultRow(0, 0, 0, 0, 0, 1, 0, 0), null,
                    manifestEntry(tenantId, runId, connectorId, sourceId, resourceId,
                        "SKIPPED", null, null, clock.instant()));
            }

            List<UUID> versionIds = cache.listSourceVersions(tenantId, connectorId, sourceId, resourceId)
                .stream().map(SourceVersionRow::versionId).sorted().toList();
            UUID tombstoneId = uuidV5(TOMBSTONE_NAMESPACE,
                nameKey(tenantId, connectorId, sourceId, resourceId, operationId.toString()));
            UUID manifestRef = uuidV5(TOMBSTONE_NAMESPACE, "versions:" + tombstoneId);
            UUID nextIncarnationId = uuidV5(TOMBSTONE_NAMESPACE, "incarnation:" + tombstoneId);
            cache.insertVersionManifestEntries(tenantId, manifestRef, versionIds);
            Instant deletedAt = clock.instant();
            SourceTombstoneRow tombstone = new SourceTombstoneRow(
                tenantId, connectorId, sourceId, resourceId, tombstoneId, deletedAt,
                context.principalId(), context.principalType(), versionIds.size(), manifestRef, nextIncarnationId
            );
            cache.insertSourceTombstoneIfAbsent(tombstone,
                sourceDeletedEvent(tombstone, runId, operationId));
            return finishRun(tenantId, runId, operationId, sourceId, startedAt, "DELETED",
                new SyncResultRow(0, 0, 0, 1, 0, 0, 0, 0), null,
                manifestEntry(tenantId, runId, connectorId, sourceId, resourceId,
                    "DELETED", null, null, deletedAt));
        } catch (Exception failure) {
            log.warn("Fixture deletion run {} failed", runId, failure);
            Instant completedAt = clock.instant();
            String errorRef = persistFailure(tenantId, operationId, runId, failure, completedAt);
            return finishRun(tenantId, runId, operationId, sourceId, startedAt, "FAILED",
                new SyncResultRow(0, 0, 0, 0, 1, 0, 0, 0), errorRef,
                manifestEntry(tenantId, runId, connectorId, sourceId, resourceId,
                    "FAILED", null, errorRef, completedAt));
        }
    }

    /** Persists the distinct zero-entry terminal and pre-discovery expiry cases. */
    public IngestionRunRow recordEmptyRun(
            VerifiedIngestionContext context,
            UUID runId,
            UUID operationId,
            String sourceId,
            EmptyRunDisposition disposition) {
        return recordEmptyRun(context, runId, operationId, sourceId, disposition, null);
    }

    public IngestionRunRow recordEmptyRun(
            VerifiedIngestionContext context,
            UUID runId,
            UUID operationId,
            String sourceId,
            EmptyRunDisposition disposition,
            Exception failure) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(disposition);
        requireText(sourceId, "sourceId");
        if (disposition == EmptyRunDisposition.EXPIRED_BEFORE_DISCOVERY) {
            return scheduleRun(context, runId, operationId, sourceId);
        }
        var duplicateRun = existingRun(context.tenantId(), runId, operationId, sourceId);
        if (duplicateRun.isPresent()) {
            return duplicateRun.get();
        }
        Instant startedAt = clock.instant();
        String status = switch (disposition) {
            case EMPTY_SOURCE -> "COMPLETED";
            case UNCHANGED_SOURCE -> "UNCHANGED";
            case SKIPPED_POLICY -> "SKIPPED";
            case CANCELLED -> "CANCELLED";
            case FAILED_BEFORE_DISCOVERY -> "FAILED";
            case EXPIRED_BEFORE_DISCOVERY -> throw new IllegalStateException("Handled above");
        };
        SyncResultRow result = new SyncResultRow(0, 0, 0, 0, 0, 0, 0, 0);
        String errorRef = disposition == EmptyRunDisposition.FAILED_BEFORE_DISCOVERY
            ? persistFailure(context.tenantId(), operationId, runId,
                failure == null ? new IllegalStateException("Ingestion failed before discovery") : failure,
                clock.instant())
            : null;
        Instant completedAt = clock.instant();
        cache.insertIngestionManifestIfAbsent(new IngestionManifestRow(
            context.tenantId(), runId, uuidV5(TOMBSTONE_NAMESPACE,
                "manifest:" + context.tenantId() + ":" + runId),
            completedAt, 0, "ingestion-manifest-entries:" + context.tenantId() + ":" + runId
        ));
        IngestionRunRow completed = new IngestionRunRow(
            context.tenantId(), runId, operationId, sourceId, status, startedAt, completedAt, result, errorRef
        );
        cache.upsertIngestionRun(completed);
        return completed;
    }

    /** Records the final per-resource disposition when acquisition fails after discovery. */
    public IngestionRunRow recordAcquisitionFailure(
            VerifiedIngestionContext context,
            UUID runId,
            UUID operationId,
            String connectorId,
            String sourceId,
            String resourceId,
            Exception failure) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(failure);
        requireText(connectorId, "connectorId");
        requireText(sourceId, "sourceId");
        validateResourceId(resourceId);

        String tenantId = context.tenantId();
        var existing = cache.readIngestionRun(tenantId, runId);
        if (existing.isPresent()) {
            IngestionRunRow run = existing.get();
            if (!run.operationId().equals(operationId) || !run.sourceId().equals(sourceId)) {
                throw new IllegalStateException("run_id is already bound to another operation or source");
            }
            if (run.completedAt() != null) {
                return run;
            }
        }

        Instant startedAt = existing.map(IngestionRunRow::startedAt).orElseGet(clock::instant);
        cache.upsertIngestionRun(new IngestionRunRow(
            tenantId, runId, operationId, sourceId, "ACQUIRING", startedAt, null, null, null
        ));
        ensureIdentity(context, connectorId, sourceId, resourceId, startedAt);

        boolean quarantined = failure instanceof ContentIntegrityException;
        String result = quarantined ? "QUARANTINED" : "FAILED";
        String errorRef = quarantined
            ? persistQuarantineFailure(tenantId, operationId, runId,
                "Acquired content failed integrity verification and was quarantined", clock.instant())
            : persistFailure(tenantId, operationId, runId, failure, clock.instant());
        SyncResultRow syncResult = new SyncResultRow(
            0, 0, 0, 0, quarantined ? 0 : 1, 0, quarantined ? 1 : 0, 0
        );
        Instant completedAt = clock.instant();
        return finishRun(tenantId, runId, operationId, sourceId, startedAt, result, syncResult, errorRef,
            manifestEntry(tenantId, runId, connectorId, sourceId, resourceId, result, null, errorRef, completedAt));
    }

    /** Persists a retryable attempt failure while leaving the run available for recovery. */
    public IngestionRunRow recordRetryableFailure(
            VerifiedIngestionContext context,
            UUID runId,
            UUID operationId,
            String sourceId,
            Exception failure) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(failure);
        requireText(sourceId, "sourceId");
        if (!retryable(failure)) {
            throw new IllegalArgumentException("failure is not retryable", failure);
        }

        String tenantId = context.tenantId();
        var existing = cache.readIngestionRun(tenantId, runId);
        if (existing.isPresent()
                && (!existing.get().operationId().equals(operationId)
                    || !existing.get().sourceId().equals(sourceId))) {
            throw new IllegalStateException("run_id is already bound to another operation or source");
        }
        if (existing.isPresent() && existing.get().completedAt() != null) {
            return existing.get();
        }
        Instant startedAt = existing.map(IngestionRunRow::startedAt).orElseGet(clock::instant);
        String errorRef = persistFailure(tenantId, operationId, runId, failure, clock.instant());
        IngestionRunRow retrying = new IngestionRunRow(
            tenantId, runId, operationId, sourceId, "ACQUIRING", startedAt, null, null, errorRef
        );
        cache.upsertIngestionRun(retrying);
        return retrying;
    }

    public IngestionRunRow scheduleRun(
            VerifiedIngestionContext context, UUID runId, UUID operationId, String sourceId) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(operationId);
        requireText(sourceId, "sourceId");
        var existing = cache.readIngestionRun(context.tenantId(), runId);
        if (existing.isPresent()) {
            if (!existing.get().operationId().equals(operationId) || !existing.get().sourceId().equals(sourceId)) {
                throw new IllegalStateException("run_id is already bound to another operation or source");
            }
            return existing.get();
        }
        IngestionRunRow scheduled = new IngestionRunRow(
            context.tenantId(), runId, operationId, sourceId, "SCHEDULED", clock.instant(), null, null, null
        );
        cache.upsertIngestionRun(scheduled);
        return scheduled;
    }

    private void ensureIdentity(
            VerifiedIngestionContext context, String connectorId, String sourceId, String resourceId, Instant now) {
        SourceRow source = cache.createSourceIfAbsent(new SourceRow(
            context.tenantId(), sourceId, connectorId, "file", "", Set.of(), "ACTIVE", now, null
        ));
        if (!"ACTIVE".equals(source.status())) {
            throw new SourceIdentityConflictException("A deleted source cannot accept new ingestion");
        }
        cache.createSourceIdentityIfAbsent(new SourceIdentityRow(
            context.tenantId(), connectorId, sourceId, resourceId
        ));
    }

    private java.util.Optional<IngestionRunRow> existingRun(
            String tenantId, UUID runId, UUID operationId, String sourceId) {
        var existing = cache.readIngestionRun(tenantId, runId);
        if (existing.isPresent()
                && (!existing.get().operationId().equals(operationId)
                    || !existing.get().sourceId().equals(sourceId))) {
            throw new IllegalStateException("run_id is already bound to another operation or source");
        }
        if (existing.isPresent() && existing.get().completedAt() == null) {
            return java.util.Optional.empty();
        }
        return existing;
    }

    private IngestionRunRow finishRun(
            String tenantId,
            UUID runId,
            UUID operationId,
            String sourceId,
            Instant startedAt,
            String status,
            SyncResultRow result,
            String errorRef,
            IngestionManifestEntryRow entry) {
        cache.upsertManifestEntry(entry);
        cache.insertIngestionManifestIfAbsent(new IngestionManifestRow(
            tenantId, runId, uuidV5(TOMBSTONE_NAMESPACE, "manifest:" + tenantId + ":" + runId),
            clock.instant(), result.entryCount(), "ingestion-manifest-entries:" + tenantId + ":" + runId
        ));
        IngestionRunRow completed = new IngestionRunRow(
            tenantId, runId, operationId, sourceId, status, startedAt, clock.instant(), result, errorRef
        );
        cache.upsertIngestionRun(completed);
        return completed;
    }

    private String persistFailure(
            String tenantId, UUID operationId, UUID runId, Exception failure, Instant occurredAt) {
        UUID errorId = UUID.randomUUID();
        String code = failure instanceof SourceRevisionConflictException
            || failure instanceof SourceIdentityConflictException
            ? "RESOURCE_CONFLICT"
            : failure instanceof ContentIntegrityException ? "INTERNAL_ERROR"
                : failure instanceof ContentStoreException || failure instanceof ContentPublicationException
                    ? "DEPENDENCY_UNAVAILABLE" : "INTERNAL_ERROR";
        int status = "RESOURCE_CONFLICT".equals(code) ? 409
            : "DEPENDENCY_UNAVAILABLE".equals(code) ? 503 : 500;
        String title = "RESOURCE_CONFLICT".equals(code) ? "Source revision conflict"
            : "DEPENDENCY_UNAVAILABLE".equals(code) ? "Content Cache unavailable" : "Internal server error";
        String detail = failure instanceof SourceRevisionConflictException
            ? failure.getMessage()
            : "RESOURCE_CONFLICT".equals(code) ? "The source ID is bound to a different connector"
            : "DEPENDENCY_UNAVAILABLE".equals(code) ? "Content Cache publication or retrieval failed"
                : "Ingestion could not complete (" + failure.getClass().getSimpleName() + ")";
        cache.insertIngestionError(new IngestionErrorRow(
            tenantId, errorId, "about:blank", title, status, detail, "urn:operation:" + operationId,
            code, "DEPENDENCY_UNAVAILABLE".equals(code), runId.toString(), "{}", occurredAt
        ));
        return errorId.toString();
    }

    private String persistQuarantineFailure(
            String tenantId, UUID operationId, UUID runId, String detail, Instant occurredAt) {
        UUID errorId = UUID.randomUUID();
        cache.insertIngestionError(new IngestionErrorRow(
            tenantId, errorId, "about:blank", "Operation failed", 500,
            detail,
            "urn:operation:" + operationId, "OPERATION_FAILED", false, runId.toString(),
            "{\"reason\":\"QUARANTINED\",\"quarantined\":1}", occurredAt
        ));
        return errorId.toString();
    }

    private static IngestionManifestEntryRow manifestEntry(
            String tenantId,
            UUID runId,
            String connectorId,
            String sourceId,
            String resourceId,
            String result,
            UUID versionId,
            String errorRef,
            Instant observedAt) {
        return new IngestionManifestEntryRow(
            tenantId, runId, connectorId, sourceId, resourceId, result, versionId, errorRef, observedAt
        );
    }

    private static String sourceRevision(ContentRef ref, int size, String digest) {
        if (ref.lastModified() != null) {
            return "modified-size:" + ref.lastModified() + ":" + size;
        }
        return "sha256:" + digest;
    }

    private static void ensureSameRevision(SourceVersionRow existing, String digest, String revision) {
        if (!existing.contentDigest().equals(digest) || !existing.sourceRevision().equals(revision)) {
            throw new SourceRevisionConflictException(
                "Connector revision collision: existing_digest=" + existing.contentDigest()
                    + ", incoming_digest=" + digest + ", existing_revision=" + existing.sourceRevision()
                    + ", incoming_revision=" + revision
            );
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static boolean retryable(Exception failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof ContentIntegrityException
                    || cause instanceof SourceRevisionConflictException
                    || cause instanceof SourceIdentityConflictException) {
                return false;
            }
            if (cause instanceof ContentStoreException || cause instanceof java.io.IOException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static SourceOutboxRow sourcePublishedEvent(
            SourceVersionRow version,
            UUID runId,
            UUID operationId,
            String workflowId,
            String correlationId,
            String causationId) {
        UUID eventId = uuidV5(TOMBSTONE_NAMESPACE, nameKey("SourcePublished", version.versionId().toString()));
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("source_identity", Map.of(
            "tenant_id", version.tenantId(),
            "connector_id", version.connectorId(),
            "source_id", version.sourceId(),
            "resource_id", version.resourceId()
        ));
        eventPayload.put("version_id", version.versionId().toString());
        eventPayload.put("content_digest", version.contentDigest());
        eventPayload.put("content_ref_id", version.contentRefId().toString());
        eventPayload.put("observed_at", version.observedAt().toString());
        eventPayload.put("run_id", runId.toString());
        eventPayload.put("operation_id", operationId.toString());
        eventPayload.put("workflow_id", workflowId);
        if (!version.contentCacheReference().isBlank()) {
            eventPayload.put("content_cache_reference", version.contentCacheReference());
        }
        Map<String, Object> envelope = eventEnvelope(
            eventId, "SourcePublished", version.tenantId(), correlationId, causationId,
            version.observedAt(), eventPayload);
        try {
            String payloadJson = MAPPER.writeValueAsString(envelope);
            return new SourceOutboxRow(
                version.tenantId(), version.connectorId(), version.sourceId(), version.resourceId(),
                eventId, "SourcePublished", version.versionId(), null, payloadJson, version.observedAt(), false
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize SourcePublished event", e);
        }
    }

    private static SourceOutboxRow sourceDeletedEvent(
            SourceTombstoneRow tombstone, UUID runId, UUID operationId) {
        UUID eventId = uuidV5(TOMBSTONE_NAMESPACE,
            nameKey("SourceDeleted", tombstone.tombstoneId().toString()));
        String workflowId = workflowId(operationId);
        String deleteCommandId = commandId(UUID.fromString(workflowId), "DELETE").toString();
        Map<String, Object> eventPayload = Map.of(
            "source_identity", Map.of(
                "tenant_id", tombstone.tenantId(),
                "connector_id", tombstone.connectorId(),
                "source_id", tombstone.sourceId(),
                "resource_id", tombstone.resourceId()
            ),
            "deleted_at", tombstone.deletedAt().toString(),
            "tombstone_id", tombstone.tombstoneId().toString(),
            "versions_manifest_ref", tombstone.versionsManifestRef().toString(),
            "run_id", runId.toString(),
            "operation_id", operationId.toString(),
            "workflow_id", workflowId
        );
        try {
            return new SourceOutboxRow(
                tombstone.tenantId(), tombstone.connectorId(), tombstone.sourceId(), tombstone.resourceId(),
                eventId, "SourceDeleted", null, tombstone.tombstoneId(),
                MAPPER.writeValueAsString(eventEnvelope(eventId, "SourceDeleted", tombstone.tenantId(),
                    workflowId, deleteCommandId, tombstone.deletedAt(), eventPayload)),
                tombstone.deletedAt(), false
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize SourceDeleted event", e);
        }
    }

    private static Map<String, Object> eventEnvelope(
            UUID eventId,
            String eventType,
            String tenantId,
            String correlationId,
            String causationId,
            Instant occurredAt,
            Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId.toString());
        envelope.put("event_type", eventType);
        envelope.put("event_version", 1);
        envelope.put("occurred_at", occurredAt.toString());
        envelope.put("producer", Map.of("service", "synflux", "version", "1"));
        envelope.put("tenant", Map.of("id", tenantId));
        envelope.put("tenant_id", tenantId);
        envelope.put("correlation_id", correlationId);
        envelope.put("causation_id", causationId);
        envelope.put("security", Map.of("classification", "UNCLASSIFIED", "policy_version", "1"));
        envelope.put("payload", payload);
        return envelope;
    }

    private static String workflowId(UUID operationId) {
        return UUID.nameUUIDFromBytes(
            ("synflux-ingestion-workflow:" + operationId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static UUID commandId(UUID workflowId, String stepId) {
        return UUID.nameUUIDFromBytes(("synflux-workflow-command:" + workflowId + ":" + stepId)
            .getBytes(StandardCharsets.UTF_8));
    }

    private static UUID sourceVersionId(
            String tenantId,
            String connectorId,
            String sourceId,
            String resourceId,
            UUID incarnationId,
            String sourceRevision) {
        return uuidV5(VERSION_NAMESPACE, nameKey(tenantId, connectorId, sourceId, resourceId,
            incarnationId == null ? "initial" : incarnationId.toString(), sourceRevision));
    }

    private static String nameKey(String... parts) {
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            key.append(part.getBytes(StandardCharsets.UTF_8).length).append(':').append(part);
        }
        return key.toString();
    }

    private static UUID uuidV5(UUID namespace, String value) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(uuidBytes(namespace));
            byte[] hash = sha1.digest(value.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            long most = 0;
            long least = 0;
            for (int i = 0; i < 8; i++) {
                most = (most << 8) | (hash[i] & 0xffL);
            }
            for (int i = 8; i < 16; i++) {
                least = (least << 8) | (hash[i] & 0xffL);
            }
            return new UUID(most, least);
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive source version ID", e);
        }
    }

    private static byte[] uuidBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        for (int index = 7; index >= 0; index--) {
            bytes[index] = (byte) most;
            most >>>= 8;
        }
        for (int index = 15; index >= 8; index--) {
            bytes[index] = (byte) least;
            least >>>= 8;
        }
        return bytes;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void validateResourceId(String resourceId) {
        requireText(resourceId, "resourceId");
        if (resourceId.startsWith("/") || resourceId.contains("\\")
                || java.util.Arrays.stream(resourceId.split("/", -1))
                    .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("resourceId must be a normalized connector-relative path");
        }
    }

    private static void validateFixtureConnector(String connectorId) {
        if (!"file".equals(connectorId)) {
            throw new IllegalArgumentException("FixtureIngestionService supports only the filesystem connector");
        }
    }

    @FunctionalInterface
    public interface ContentPublisher {
        UUID publish(byte[] bytes, UUID sourceVersionId) throws Exception;
    }

    private static final class ContentPublicationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ContentPublicationException(Exception cause) {
            super(cause);
        }
    }

    public enum DeletionSignal {
        DELETED,
        NOT_VISIBLE,
        NOT_FOUND,
        ACCESS_DENIED,
        SYNC_INCOMPLETE,
        UNKNOWN
    }

    public enum EmptyRunDisposition {
        FAILED_BEFORE_DISCOVERY,
        EMPTY_SOURCE,
        UNCHANGED_SOURCE,
        SKIPPED_POLICY,
        CANCELLED,
        EXPIRED_BEFORE_DISCOVERY
    }
}
