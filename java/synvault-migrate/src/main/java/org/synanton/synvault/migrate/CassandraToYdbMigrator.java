package org.synanton.synvault.migrate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.PageRequest;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.synvault.api.Chunk;
import org.synanton.synvault.api.ChunkPage;
import org.synanton.synvault.api.ChunkQuery;
import org.synanton.synvault.api.Document;
import org.synanton.synvault.api.DocumentRevision;
import org.synanton.synvault.api.ProvenanceRecord;
import org.synanton.synvault.api.PublicationIntent;
import org.synanton.synvault.api.RevisionMetadata;
import org.synanton.synvault.api.RevisionWriteOptions;
import org.synanton.synvault.api.SynvaultStore;

/**
 * 035 PoC-scope migration: Cassandra (+ ingestion-cache shapes behind the
 * Cassandra adapter) → YDB, through the port abstractions — never backend APIs.
 *
 * <p>Each source document becomes one fresh revision on the target with a
 * migration-marked publication intent. Validation is checksum-per-document
 * (canonical serialization); rollback deletes migrated documents. Dry-run
 * computes checksums without writing.
 */
public class CassandraToYdbMigrator {

    public record MigrationPlan(
            SynvaultStore source,
            SynvaultStore target,
            SecurityContext context,
            List<DocumentId> documents,
            boolean dryRun) {}

    public record DocumentMigration(
            DocumentId id, String sourceChecksum, String targetChecksum, boolean match) {}

    public record MigrationResult(List<DocumentMigration> documents, boolean rollbackSupported) {
        public boolean allMatch() {
            return documents.stream().allMatch(DocumentMigration::match);
        }
    }

    public CompletionStage<MigrationResult> migrate(MigrationPlan plan) {
        List<DocumentMigration> out = new ArrayList<>();
        for (DocumentId id : plan.documents()) {
            out.add(migrateOne(plan, id));
        }
        return CompletableFuture.completedFuture(new MigrationResult(List.copyOf(out), true));
    }

    private DocumentMigration migrateOne(MigrationPlan plan, DocumentId id) {
        SecurityContext ctx = plan.context();
        Document doc =
                plan.source().getDocument(ctx, id).toCompletableFuture().join()
                        .orElseThrow(() -> new IllegalStateException("source document missing: " + id));
        List<Chunk> chunks = readAllChunks(plan.source(), ctx, id);
        List<ProvenanceRecord> provenance =
                plan.source().getProvenance(ctx, id).toCompletableFuture().join();
        String sourceChecksum = checksum(doc, chunks, provenance);
        if (!plan.dryRun()) {
            DocumentRevision revision =
                    new DocumentRevision(
                            new Document(
                                    doc.id(), doc.title(), doc.sourceUri(), doc.metadata(), 0,
                                    doc.createdAt(), Instant.now()),
                            chunks,
                            provenance.isEmpty() ? List.of(placeholderProvenance(chunks)) : provenance,
                            new RevisionMetadata(
                                    0, PrincipalRef.service("migrator"), Instant.now()),
                            new PublicationIntent(
                                    "migrate-" + id.value() + "-" + System.currentTimeMillis(),
                                    ctx.tenantScope().tenantId(),
                                    "{\"op\":\"migrate\",\"source\":\"cassandra\"}"));
            plan.target()
                    .putDocumentRevision(ctx, revision, RevisionWriteOptions.unconditional())
                    .toCompletableFuture()
                    .join();
        }
        Document reread =
                plan.dryRun()
                        ? doc
                        : plan.target().getDocument(ctx, id).toCompletableFuture().join()
                                .orElseThrow(() -> new IllegalStateException("target document missing: " + id));
        List<Chunk> rechunks =
                plan.dryRun()
                        ? chunks
                        : readAllChunks(plan.target(), ctx, id);
        List<ProvenanceRecord> reprov =
                plan.dryRun()
                        ? provenance
                        : plan.target().getProvenance(ctx, id).toCompletableFuture().join();
        String targetChecksum = checksum(reread, rechunks, reprov);
        return new DocumentMigration(id, sourceChecksum, targetChecksum, sourceChecksum.equals(targetChecksum));
    }

    /** PoC-scope rollback: deletes migrated documents from the target. */
    public CompletionStage<Void> rollback(SynvaultStore target, SecurityContext ctx, List<DocumentId> ids) {
        for (DocumentId id : ids) {
            try {
                target.deleteDocument(ctx, id).toCompletableFuture().join();
            } catch (Exception e) {
                // Cassandra target cannot delete (008 UNSUPPORTED) — rollback is YDB-directed.
                throw new IllegalStateException("rollback failed for " + id + ": " + e.getMessage(), e);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private static List<Chunk> readAllChunks(SynvaultStore store, SecurityContext ctx, DocumentId id) {
        List<Chunk> out = new ArrayList<>();
        Optional<String> cursor = Optional.empty();
        while (true) {
            ChunkPage page =
                    store.getChunks(
                                    ctx, id, ChunkQuery.all(),
                                    cursor.isPresent()
                                            ? PageRequest.after(100, cursor.get())
                                            : PageRequest.first(100))
                            .toCompletableFuture()
                            .join();
            out.addAll(page.items());
            if (page.nextCursor().isEmpty()) {
                return out.stream().sorted(Comparator.comparingInt(Chunk::ordinal)).toList();
            }
            cursor = page.nextCursor();
        }
    }

    private static ProvenanceRecord placeholderProvenance(List<Chunk> chunks) {
        // Source revisions without provenance cannot migrate as revisions (invariant 12
        // is type-level). PoC reports them as mismatches instead of fabricating lineage.
        throw new IllegalStateException(
                "source document has no provenance; refusing to fabricate lineage on "
                        + chunks.size() + " chunks");
    }

    /** Canonical checksum: title + source URI + ordered chunk texts + provenance keys. */
    static String checksum(Document doc, List<Chunk> chunks, List<ProvenanceRecord> provenance) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            Map<String, String> orderedMeta = new TreeMap<>(doc.metadata());
            sha.update(canonical(doc.id().value(), doc.title(), doc.sourceUri(), orderedMeta.toString()));
            chunks.stream()
                    .sorted(Comparator.comparingInt(Chunk::ordinal))
                    .forEach(c -> sha.update(canonical(
                            c.id().value(), c.text(), String.valueOf(c.tokenCount()),
                            new TreeMap<>(c.metadata()).toString())));
            provenance.stream()
                    .sorted(Comparator.comparing(p -> p.chunkId().value()))
                    .forEach(p -> sha.update(canonical(
                            p.chunkId().value(), p.extractor(), p.sourceVersionId().value())));
            return HexFormat.of().formatHex(sha.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] canonical(String... parts) {
        return String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8);
    }
}
