package org.synanton.synvault.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.PageRequest;
import org.synanton.storage.contract.SecurityContext;

/**
 * Persistence port used by Knowledge 1.25 for canonical knowledge (proposal §9.1).
 *
 * <p>Ownership: persists state owned by Knowledge 1.25; never a competing domain
 * authority. Source-version authority stays with Ingestion 1.28.
 *
 * <p>Atomicity: {@link #putDocumentRevision} commits document + chunks + provenance +
 * publication record atomically when the backend supports it. An adapter that cannot
 * satisfy the contract reports {@code supportsTransactions=false} and rejects revisions
 * with {@code UNSUPPORTED} instead of silently weakening the guarantee.
 *
 * <p>{@link #putDocument} is metadata-only: it never writes chunks, provenance, or a
 * publication record and never substitutes for {@link #putDocumentRevision}.
 */
public interface SynvaultStore {

    CompletionStage<Document> putDocument(
            SecurityContext context, Document document, DocumentWriteOptions options);

    CompletionStage<Optional<Document>> getDocument(SecurityContext context, DocumentId id);

    CompletionStage<Void> deleteDocument(SecurityContext context, DocumentId id);

    CompletionStage<ChunkPage> getChunks(
            SecurityContext context, DocumentId documentId, ChunkQuery query, PageRequest page);

    CompletionStage<Void> putDocumentRevision(
            SecurityContext context, DocumentRevision revision, RevisionWriteOptions options);

    /** Provenance lookup for a document's chunks (mandatory-provenance verification). */
    CompletionStage<List<ProvenanceRecord>> getProvenance(SecurityContext context, DocumentId id);

    StoreCapabilities capabilities();
}
