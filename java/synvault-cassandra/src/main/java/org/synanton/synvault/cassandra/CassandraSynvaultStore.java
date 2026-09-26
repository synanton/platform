package org.synanton.synvault.cassandra;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.AnnotationRow;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.contract.ConformanceEntry;
import org.synanton.storage.contract.ConformanceMatrix;
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
import org.synanton.synvault.api.RevisionWriteOptions;
import org.synanton.synvault.api.StoreCapabilities;
import org.synanton.synvault.api.SynvaultStore;

/**
 * Cassandra {@link SynvaultStore} adapter: the only module (besides
 * {@code ingestion-cache} itself) allowed to touch Cassandra concepts.
 *
 * <p>Honest capability subset for Phase 0B (full parity is YDB-POC-021, gated on the
 * YDB-POC-008 atomicity decision):
 *
 * <ul>
 *   <li>Document metadata CRUD via {@code manifest} rows. Title, metadata, and the
 *       internal storage revision round-trip inside the {@code ingest_usage} JSON
 *       envelope (legacy-column reuse, documented here — the YDB schema in §11.1
 *       has first-class columns).</li>
 *   <li>Chunk reads via {@code chunks} rows with in-adapter pagination.</li>
 *   <li>Provenance reads via {@code annotations} rows
 *       ({@code target_type = "synvault-chunk"}).</li>
 *   <li>{@code putDocumentRevision} and {@code deleteDocument} report
 *       {@code UNSUPPORTED}: Cassandra cannot satisfy the §9.1 atomicity contract,
 *       and the port forbids silently weakening it. No best-effort path exists
 *       until YDB-POC-008 approves one.</li>
 * </ul>
 *
 * <p>Document ids map deterministically to content UUIDs
 * ({@code nameUUID("synvault-doc:" + tenant + ":" + id)}); chunk {@code chunkSha256}
 * values written by this adapter are adapter-internal dedup hints, never content
 * digests (source identity vs content digest stay distinct per invariant 9).
 */
public class CassandraSynvaultStore implements SynvaultStore, Conformant {

    /** Annotation target type for chunk provenance written through this port. */
    static final String PROVENANCE_TARGET_TYPE = "synvault-chunk";

    private static final String PORT_MARKER = "synvault-port/v1";

    private final IngestionCacheClient client;
    private final String namespace;

    public CassandraSynvaultStore(IngestionCacheClient client) {
        this(client, "");
    }

    /**
     * Test/partitioned constructor: storage keys are namespaced so parallel stores
     * (e.g. per-test contract instances) stay isolated without schema changes.
     */
    public CassandraSynvaultStore(IngestionCacheClient client, String namespace) {
        this.client = client;
        this.namespace = namespace == null ? "" : namespace;
    }

    @Override
    public CompletionStage<Document> putDocument(
            SecurityContext context, Document document, DocumentWriteOptions options) {
        String tenant = tenant(context);
        UUID ref = ref(tenant, document.id());
        long revision =
                client.readManifest(tenant, ref).map(row -> Codec.revision(row.ingestUsage())).orElse(0L);
        Instant now = Instant.now();
        ManifestRow row =
                new ManifestRow(
                        tenant,
                        ref,
                        now,
                        1,
                        PORT_MARKER,
                        1,
                        "SYNVAULT",
                        "",
                        "",
                        document.sourceUri(),
                        "",
                        0L,
                        "application/octet-stream",
                        "",
                        "",
                        Codec.envelope(document.title(), document.metadata(), revision));
        client.upsertManifest(row);
        Document stored =
                new Document(
                        document.id(),
                        document.title(),
                        document.sourceUri(),
                        document.metadata(),
                        revision,
                        now,
                        now);
        return CompletableFuture.completedFuture(stored);
    }

    @Override
    public CompletionStage<Optional<Document>> getDocument(SecurityContext context, DocumentId id) {
        String tenant = tenant(context);
        return CompletableFuture.completedFuture(
                client.readManifest(tenant, ref(tenant, id))
                        .map(row -> Codec.toDocument(id, row)));
    }

    @Override
    public CompletionStage<Void> deleteDocument(SecurityContext context, DocumentId id) {
        return CompletableFuture.failedFuture(
                new StorageException(
                        StorageErrorKind.UNSUPPORTED,
                        "UNSUPPORTED: deleteDocument has no Cassandra mapping until YDB-POC-021"));
    }

    @Override
    public CompletionStage<ChunkPage> getChunks(
            SecurityContext context, DocumentId documentId, ChunkQuery query, PageRequest page) {
        String tenant = tenant(context);
        List<Chunk> filtered =
                client.readChunks(tenant, ref(tenant, documentId)).stream()
                        .map(row -> toChunk(documentId, row))
                        .filter(c -> matches(c.metadata(), query.mustMatchMetadata()))
                        .sorted(Comparator.comparingInt(Chunk::ordinal))
                        .toList();
        int resumeAfter = page.cursor().map(Integer::parseInt).orElse(-1);
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
        return CompletableFuture.failedFuture(
                new StorageException(
                        StorageErrorKind.UNSUPPORTED,
                        "UNSUPPORTED: putDocumentRevision requires multi-table atomicity "
                                + "Cassandra cannot provide; tracked by YDB-POC-008"));
    }

    @Override
    public CompletionStage<List<ProvenanceRecord>> getProvenance(SecurityContext context, DocumentId id) {
        String tenant = tenant(context);
        UUID ref = ref(tenant, id);
        List<ProvenanceRecord> out = new ArrayList<>();
        for (ChunkRow chunk : client.readChunks(tenant, ref)) {
            ChunkId chunkId = ChunkId.of(id.value() + ":o" + chunk.chunkOrdinal());
            for (AnnotationRow row :
                    client.readAnnotations(tenant, PROVENANCE_TARGET_TYPE, chunkId.value())) {
                Codec.toProvenance(chunkId, row).ifPresent(out::add);
            }
        }
        return CompletableFuture.completedFuture(out);
    }

    @Override
    public StoreCapabilities capabilities() {
        // supportsStorageRevisions is false: OCC is undemonstrable while revisions are
        // UNSUPPORTED (008). Flags advertise demonstrated capabilities only.
        return new StoreCapabilities(false, ConsistencyLevel.EVENTUAL, false, false, true, true);
    }

    @Override
    public String adapterName() {
        return "cassandra";
    }

    @Override
    public String adapterVersion() {
        return "1.0.0";
    }

    @Override
    public ConformanceMatrix conformance() {
        String evidence = "org.synanton.synvault.cassandra.CassandraSynvaultStoreTest";
        return new ConformanceMatrix(
                adapterName(),
                adapterVersion(),
                List.of(
                        ConformanceEntry.unsupported(
                                Capabilities.SYNVAULT_REVISION,
                                "008: multi-table atomicity unavailable on Cassandra"),
                        ConformanceEntry.unsupported(
                                Capabilities.SYNVAULT_DELETE,
                                "008: no delete mapping until YDB-POC-021"),
                        ConformanceEntry.supported(Capabilities.SYNVAULT_DOCUMENT, evidence),
                        ConformanceEntry.supported(Capabilities.SYNVAULT_CHUNKS, evidence),
                        ConformanceEntry.supported(Capabilities.SYNVAULT_PROVENANCE, evidence),
                        ConformanceEntry.supported(Capabilities.SYNVAULT_PAGINATION, evidence),
                        ConformanceEntry.unsupported(
                                Capabilities.SYNVAULT_OCC,
                                "008: OCC undemonstrable while revisions are unsupported")));
    }

    private String tenant(SecurityContext context) {
        String tenant = context.tenantScope().tenantId();
        return namespace.isEmpty() ? tenant : namespace + "|" + tenant;
    }

    private static UUID ref(String tenant, DocumentId id) {
        return UUID.nameUUIDFromBytes(("synvault-doc:" + tenant + ":" + id.value()).getBytes(StandardCharsets.UTF_8));
    }

    private static Chunk toChunk(DocumentId documentId, ChunkRow row) {
        return new Chunk(
                ChunkId.of(documentId.value() + ":o" + row.chunkOrdinal()),
                documentId,
                row.chunkOrdinal(),
                row.chunkText(),
                row.tokenCount(),
                Codec.chunkMetadata(row),
                null);
    }

    private static boolean matches(Map<String, String> metadata, Map<String, String> required) {
        for (Map.Entry<String, String> entry : required.entrySet()) {
            if (!entry.getValue().equals(metadata.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Legacy-column JSON envelope + provenance codec. Zero-dependency on purpose. */
    static final class Codec {
        private Codec() {}

        static String envelope(String title, Map<String, String> metadata, long revision) {
            StringBuilder sb = new StringBuilder("{\"t\":\"");
            sb.append(escape(title)).append("\",\"m\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : metadata.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append('"');
            }
            sb.append("},\"rev\":").append(revision).append('}');
            return sb.toString();
        }

        static long revision(String envelope) {
            int at = envelope == null ? -1 : envelope.lastIndexOf("\"rev\":");
            if (at < 0) {
                return 0L;
            }
            try {
                return Long.parseLong(envelope.substring(at + 6).replaceAll("[^0-9-].*", ""));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        static Document toDocument(DocumentId id, ManifestRow row) {
            String envelope = row.ingestUsage() == null ? "" : row.ingestUsage();
            String title = field(envelope, "t");
            return new Document(
                    id,
                    title == null ? "" : unescape(title),
                    row.sourceUri() == null ? "" : row.sourceUri(),
                    metadata(envelope),
                    revision(envelope),
                    row.ingestedAt(),
                    row.ingestedAt());
        }

        static Map<String, String> chunkMetadata(ChunkRow row) {
            var out = new java.util.HashMap<String, String>();
            if (row.sectionPath() != null && !row.sectionPath().isBlank()) {
                out.put("section", row.sectionPath());
            }
            if (row.heading() != null && !row.heading().isBlank()) {
                out.put("heading", row.heading());
            }
            return Map.copyOf(out);
        }

        static Optional<ProvenanceRecord> toProvenance(ChunkId chunkId, AnnotationRow row) {
            // value envelope: {"ex":"...","sv":"...","page":N,"s":N,"e":N}
            try {
                String value = row.value();
                String extractor = field(value, "ex");
                String sv = field(value, "sv");
                if (extractor == null || sv == null) {
                    return Optional.empty();
                }
                return Optional.of(
                        new ProvenanceRecord(
                                chunkId,
                                extractor,
                                org.synanton.storage.contract.SourceVersionId.of(sv),
                                Optional.empty(),
                                number(value, "page"),
                                number(value, "s"),
                                number(value, "e")));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String unescape(String s) {
            return s.replace("\\\"", "\"").replace("\\\\", "\\");
        }

        private static Map<String, String> metadata(String envelope) {
            String marker = "\"m\":{";
            int at = envelope.indexOf(marker);
            if (at < 0) {
                return Map.of();
            }
            int end = envelope.indexOf('}', at + marker.length());
            if (end < 0) {
                return Map.of();
            }
            String body = envelope.substring(at + marker.length(), end);
            if (body.isBlank()) {
                return Map.of();
            }
            var out = new java.util.HashMap<String, String>();
            for (String pair : body.split("\",\"")) {
                String[] kv = pair.replaceFirst("^\"", "").split("\":\"", 2);
                if (kv.length == 2) {
                    out.put(unescape(kv[0]), unescape(kv[1].replaceFirst("\"$", "")));
                }
            }
            return Map.copyOf(out);
        }

        private static String field(String json, String key) {
            String marker = "\"" + key + "\":\"";
            int at = json.indexOf(marker);
            if (at < 0) {
                return null;
            }
            int end = json.indexOf('"', at + marker.length());
            return end < 0 ? null : json.substring(at + marker.length(), end);
        }

        private static int number(String json, String key) {
            String marker = "\"" + key + "\":";
            int at = json.indexOf(marker);
            if (at < 0) {
                return 0;
            }
            try {
                return Integer.parseInt(
                        json.substring(at + marker.length()).replaceAll("[^0-9-].*", ""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
