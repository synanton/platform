package org.synanton.synvault.postgres;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.SourceVersionId;
import org.synanton.storage.contract.StorageErrorKind;
import org.synanton.storage.contract.StorageException;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synvault.api.Chunk;
import org.synanton.synvault.api.Document;
import org.synanton.synvault.api.DocumentRevision;
import org.synanton.synvault.api.DocumentWriteOptions;
import org.synanton.synvault.api.ProvenanceRecord;
import org.synanton.synvault.api.PublicationIntent;
import org.synanton.synvault.api.RevisionMetadata;
import org.synanton.synvault.api.RevisionWriteOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG-POC-004 negative atomicity: a mid-transaction failure leaves nothing
 * behind (no document, no chunks, no provenance, no publication record).
 * Uses the {@code failAfterChunks} hook — same shape as the YDB adapter's
 * negative test.
 */
class RevisionAtomicityTest extends PostgresTestBase {

    private static final PolicyContext POLICY = new PolicyContext("test-policy", "v1");

    private static SecurityContext ctx(String tenant) {
        return SecurityContext.user(
                TenantScope.of(tenant), PrincipalRef.user("u-1"), POLICY);
    }

    private static PostgresSynvaultStore store() {
        try (Connection probe = PostgresTestBase.connection()) {
            org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
            ds.setUrl(probe.getMetaData().getURL());
            ds.setUser("app");
            ds.setPassword("app");
            return new PostgresSynvaultStore(ds);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void failedRevisionLeavesNothingBehind() throws Exception {
        ensureStarted();
        PostgresSynvaultStore store = store();
        var now = java.time.Instant.now();
        var docId = DocumentId.of("neg-doc-1");
        var chunkId = ChunkId.of("neg-doc-1-c0");
        var revision =
                new DocumentRevision(
                        new Document(docId, "t", "cache://neg", Map.of(), 0, now, now),
                        List.of(new Chunk(chunkId, docId, 0, "text", 1, Map.of(), null)),
                        List.of(
                                new ProvenanceRecord(
                                        chunkId, "ext", SourceVersionId.of("sv-1"),
                                        Optional.empty(), 1, 0, 4)),
                        new RevisionMetadata(0, PrincipalRef.user("u-1"), now),
                        new PublicationIntent("neg-rev-1", "tenant-neg-rollback", "{\"op\":\"put\"}"));

        store.failAfterChunks = true;
        assertThatThrownBy(
                        () ->
                                store.putDocumentRevision(
                                                ctx("tenant-neg-rollback"), revision, RevisionWriteOptions.unconditional())
                                        .toCompletableFuture()
                                        .join())
                .hasStackTraceContaining("injected failure");
        store.failAfterChunks = false;

        assertThat(store.getDocument(ctx("tenant-neg-rollback"), docId).toCompletableFuture().join()).isEmpty();
        assertThat(
                        store.getProvenance(ctx("tenant-neg-rollback"), docId).toCompletableFuture().join())
                .isEmpty();
        try (Connection admin = PostgresTestBase.adminConnection();
                var ps =
                        admin.prepareStatement(
                                "SELECT count(*) FROM chunks WHERE tenant_id = ? AND doc_id = ?")) {
            // Admin bypasses RLS scoping questions; count raw rows.
            ps.setString(1, "tenant-neg-rollback");
            ps.setString(2, "neg-doc-1");
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("no chunks leaked").isZero();
            }
        }
        try (Connection admin = PostgresTestBase.adminConnection();
                var ps =
                        admin.prepareStatement(
                                "SELECT count(*) FROM publication_log WHERE tenant_id = ?")) {
            ps.setString(1, "tenant-neg-rollback");
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("no publication leaked").isZero();
            }
        }
    }

    private static DocumentRevision revisionFor(String doc, String suffix, long generation) {
        var now = java.time.Instant.now();
        var docId = DocumentId.of(doc);
        var chunkId = ChunkId.of(doc + "-c0-" + suffix);
        return new DocumentRevision(
                new Document(docId, "t", "cache://" + doc, Map.of(), 0, now, now),
                List.of(new Chunk(chunkId, docId, 0, "text", 1, Map.of(), null)),
                List.of(
                        new ProvenanceRecord(
                                chunkId, "ext", SourceVersionId.of("sv-1"),
                                Optional.empty(), 1, 0, 4)),
                new RevisionMetadata(generation, PrincipalRef.user("u-1"), now),
                new PublicationIntent("rev-" + doc + "-" + suffix, "tenant-race",
                        "{\"op\":\"put\"}"));
    }

    @Test
    void concurrentRacersOnSameExpectedRevisionOneWins() throws Exception {
        // Two threads race putDocumentRevision with expectRevision(0) on the
        // same document. SELECT ... FOR UPDATE serializes the transactions:
        // exactly one commits with revision 0, the other gets CONFLICT and
        // leaves no partial rows (its whole transaction rolls back).
        ensureStarted();
        PostgresSynvaultStore store = store();
        PostgresSynvaultStore store2 = store();
        var now = java.time.Instant.now();
        var docId = DocumentId.of("race-doc-1");
        store.putDocument(
                        ctx("tenant-race"),
                        new Document(docId, "t", "cache://race", Map.of(), 0, now, now),
                        DocumentWriteOptions.upserting())
                .toCompletableFuture()
                .join();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Long> racer1 = pool.submit(() -> {
            try {
                store.putDocumentRevision(
                                ctx("tenant-race"), revisionFor("race-doc-1", "a", 0),
                                RevisionWriteOptions.expectRevision(0))
                        .toCompletableFuture()
                        .join();
                return 0L;
            } catch (Exception e) {
                throw new RuntimeException(describe(e));
            }
        });
        Future<Long> racer2 = pool.submit(() -> {
            try {
                store2.putDocumentRevision(
                                ctx("tenant-race"), revisionFor("race-doc-1", "b", 0),
                                RevisionWriteOptions.expectRevision(0))
                        .toCompletableFuture()
                        .join();
                return 0L;
            } catch (Exception e) {
                throw new RuntimeException(describe(e));
            }
        });
        // Both racers run at once; SELECT ... FOR UPDATE serializes them.

        int wins = 0;
        int conflicts = 0;
        for (Future<Long> f : List.of(racer1, racer2)) {
            try {
                assertThat(f.get(30, TimeUnit.SECONDS)).isZero();
                wins++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause().getMessage()).contains("CONFLICT");
                conflicts++;
            }
        }
        assertThat(wins).as("exactly one racer wins").isOne();
        assertThat(conflicts).as("exactly one racer conflicts").isOne();

        // Loser's chunks/provenance must have rolled back: exactly one chunk
        // (the winner's) before the retry below adds a second.
        try (Connection admin = PostgresTestBase.adminConnection();
                var ps =
                        admin.prepareStatement(
                                "SELECT count(*) FROM chunks WHERE tenant_id = ? AND doc_id = ?")) {
            ps.setString(1, "tenant-race");
            ps.setString(2, "race-doc-1");
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("loser left no chunks").isOne();
            }
        }

        // Retry-after-reread: the loser rereads (revision now 0, winner wrote
        // generation 0) and commits generation 1 with expectRevision(0).
        long current =
                store.getDocument(ctx("tenant-race"), DocumentId.of("race-doc-1"))
                        .toCompletableFuture()
                        .join()
                        .orElseThrow()
                        .storageRevision();
        store2.putDocumentRevision(
                        ctx("tenant-race"), revisionFor("race-doc-1", "b-retry", current + 1),
                        RevisionWriteOptions.expectRevision(current))
                .toCompletableFuture()
                .join();
        assertThat(
                        store.getDocument(ctx("tenant-race"), DocumentId.of("race-doc-1"))
                                .toCompletableFuture()
                                .join()
                                .orElseThrow()
                                .storageRevision())
                .isEqualTo(current + 1);

        pool.shutdownNow();
    }

    private static String describe(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        if (t instanceof StorageException se && se.kind() == StorageErrorKind.CONFLICT) {
            return "CONFLICT: " + se.getMessage();
        }
        return "OTHER: " + t;
    }

    @Test
    void putDocumentCreatesNoChunksProvenanceOrPublications() throws Exception {        // putDocument is metadata-only at the port level; this test pins the
        // publication_log half, which the shared contract suite does not cover.
        ensureStarted();
        PostgresSynvaultStore store = store();
        var now = java.time.Instant.now();
        var docId = DocumentId.of("neg-doc-2");
        store.putDocument(
                        ctx("tenant-neg-put"),
                        new Document(docId, "t", "cache://neg2", Map.of("k", "v"), 0, now, now),
                        DocumentWriteOptions.upserting())
                .toCompletableFuture()
                .join();
        try (Connection admin = PostgresTestBase.adminConnection();
                var ps =
                        admin.prepareStatement(
                                "SELECT (SELECT count(*) FROM chunks WHERE tenant_id = ? AND doc_id = ?)"
                                        + " + (SELECT count(*) FROM publication_log WHERE tenant_id = ?)")) {
            ps.setString(1, "tenant-neg-put");
            ps.setString(2, "neg-doc-2");
            ps.setString(3, "tenant-neg-put");
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("no chunks or publications from putDocument").isZero();
            }
        }
    }
}
