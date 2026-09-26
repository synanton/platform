package org.synanton.synvault.inmemory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.PageRequest;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.StorageErrorKind;
import org.synanton.storage.contract.StorageException;
import org.synanton.synvault.api.Chunk;
import org.synanton.synvault.api.ChunkPage;
import org.synanton.synvault.api.ChunkQuery;
import org.synanton.synvault.api.ConsistencyLevel;
import org.synanton.synvault.api.Document;
import org.synanton.synvault.api.DocumentRevision;
import org.synanton.synvault.api.DocumentWriteOptions;
import org.synanton.synvault.api.ProvenanceRecord;
import org.synanton.synvault.api.PublicationIntent;
import org.synanton.synvault.api.RevisionWriteOptions;
import org.synanton.synvault.api.StoreCapabilities;
import org.synanton.synvault.api.SynvaultStore;

/**
 * In-memory {@link SynvaultStore} for tests and lightweight development.
 * Fully transactional (single-JVM lock); supports revisions, OCC, and cursor pagination.
 */
public class InMemorySynvaultStore implements SynvaultStore {

    private record Key(String tenantId, String documentId) {}

    private static final class DocState {
        Document document;
        List<Chunk> chunks = List.of();
        List<ProvenanceRecord> provenance = List.of();
        PublicationIntent publication;
    }

    private final ConcurrentHashMap<Key, DocState> states = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<Document> putDocument(
            SecurityContext context, Document document, DocumentWriteOptions options) {
        Key key = key(context, document.id());
        DocState state = states.computeIfAbsent(key, k -> new DocState());
        synchronized (state) {
            Document stored =
                    new Document(
                            document.id(),
                            document.title(),
                            document.sourceUri(),
                            document.metadata(),
                            state.document == null ? 0 : state.document.storageRevision(),
                            state.document == null ? Instant.now() : state.document.createdAt(),
                            Instant.now());
            state.document = stored;
            return CompletableFuture.completedFuture(stored);
        }
    }

    @Override
    public CompletionStage<Optional<Document>> getDocument(SecurityContext context, DocumentId id) {
        DocState state = states.get(key(context, id));
        return CompletableFuture.completedFuture(
                Optional.ofNullable(state == null ? null : state.document));
    }

    @Override
    public CompletionStage<Void> deleteDocument(SecurityContext context, DocumentId id) {
        states.remove(key(context, id));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<ChunkPage> getChunks(
            SecurityContext context, DocumentId documentId, ChunkQuery query, PageRequest page) {
        DocState state = states.get(key(context, documentId));
        if (state == null) {
            return CompletableFuture.completedFuture(new ChunkPage(List.of(), Optional.empty()));
        }
        List<Chunk> filtered;
        synchronized (state) {
            filtered =
                    state.chunks.stream()
                            .filter(c -> matches(c.metadata(), query.mustMatchMetadata()))
                            .sorted(Comparator.comparingInt(Chunk::ordinal))
                            .toList();
        }
        int resumeAfter =
                page.cursor().map(cursor -> Integer.parseInt(cursor)).orElse(-1);
        List<Chunk> window =
                filtered.stream().filter(c -> c.ordinal() > resumeAfter).limit(page.limit() + 1).toList();
        List<Chunk> items = window.stream().limit(page.limit()).toList();
        Optional<String> nextCursor =
                window.size() > items.size()
                        ? Optional.of(String.valueOf(items.get(items.size() - 1).ordinal()))
                        : Optional.empty();
        return CompletableFuture.completedFuture(new ChunkPage(items, nextCursor));
    }

    @Override
    public CompletionStage<Void> putDocumentRevision(
            SecurityContext context, DocumentRevision revision, RevisionWriteOptions options) {
        Key key = key(context, revision.document().id());
        DocState state = states.computeIfAbsent(key, k -> new DocState());
        synchronized (state) {
            long current = state.document == null ? 0 : state.document.storageRevision();
            if (options.expectedRevision().isPresent()
                    && options.expectedRevision().get() != current) {
                return CompletableFuture.failedFuture(
                        new StorageException(
                                StorageErrorKind.CONFLICT,
                                "CONFLICT: expected revision "
                                        + options.expectedRevision().get()
                                        + " but stored is "
                                        + current));
            }
            long committed = current + 1;
            Instant now = Instant.now();
            state.document =
                    new Document(
                            revision.document().id(),
                            revision.document().title(),
                            revision.document().sourceUri(),
                            revision.document().metadata(),
                            committed,
                            state.document == null ? now : state.document.createdAt(),
                            now);
            state.chunks = List.copyOf(revision.chunks());
            state.provenance = List.copyOf(revision.provenance());
            state.publication = revision.publication();
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public CompletionStage<List<ProvenanceRecord>> getProvenance(SecurityContext context, DocumentId id) {
        DocState state = states.get(key(context, id));
        return CompletableFuture.completedFuture(
                state == null ? List.of() : List.copyOf(state.provenance));
    }

    @Override
    public StoreCapabilities capabilities() {
        return new StoreCapabilities(true, ConsistencyLevel.STRONG, false, true, true, true);
    }

    private static Key key(SecurityContext context, DocumentId id) {
        return new Key(context.tenantScope().tenantId(), id.value());
    }

    private static boolean matches(Map<String, String> metadata, Map<String, String> required) {
        for (Map.Entry<String, String> entry : required.entrySet()) {
            if (!entry.getValue().equals(metadata.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
