package org.synanton.synquest.postgres;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.sql.DataSource;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.contract.ConformanceEntry;
import org.synanton.storage.contract.ConformanceMatrix;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.api.IndexStatus;
import org.synanton.synquest.api.RebuildOptions;
import org.synanton.synquest.api.SchemaOptions;
import org.synanton.synquest.api.SearchCapabilities;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.SearchResult;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.SynquestIndexAdmin;
import org.synanton.synquest.api.SynquestIndexWriter;

/**
 * PG-POC-007 retrieval adapter (scaffold, 007-1).
 *
 * <p>Implements the full port surface ({@link SynquestEngine}, {@link
 * SynquestIndexWriter}, {@link SynquestIndexAdmin}) with honest
 * {@code UnsupportedOperationException} stubs — retrieval semantics land
 * incrementally in 007-2 onward. Per the §9.3 discipline every capability
 * flag reports {@code false} until evidenced; the conformance matrix marks
 * the retrieval entries {@code unverified}, never supported-by-assertion.
 *
 * <p>Reads the same {@code documents}/{@code chunks}/{@code provenance}
 * tables the {@code synvault-postgres} adapter writes (PG-POC-004); RLS
 * scoping via {@code SET LOCAL} follows the store's shape.
 */
public class PostgresSynquestEngine
        implements SynquestEngine, SynquestIndexWriter, SynquestIndexAdmin, Conformant {

    private final DataSource dataSource;

    public PostgresSynquestEngine(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static UnsupportedOperationException todo(String method, String ticket) {
        return new UnsupportedOperationException(
                "PG-POC-007 scaffold (007-1): " + method + " lands in " + ticket);
    }

    // ---- search ----

    @Override
    public CompletionStage<SearchResult> search(
            org.synanton.storage.contract.SecurityContext context, SearchRequest request) {
        return CompletableFuture.failedFuture(todo("search", "007-2+"));
    }

    @Override
    public SearchCapabilities capabilities() {
        // §9.3: unverified flags report false. Each flips with its evidence task.
        return new SearchCapabilities(false, false, false, false, false, false, false, false);
    }

    // ---- writer ----

    @Override
    public CompletionStage<Void> upsert(List<ChunkProjection> projections) {
        return CompletableFuture.failedFuture(todo("upsert", "007-5"));
    }

    @Override
    public CompletionStage<Void> delete(GenerationId generationId, Collection<ChunkId> ids) {
        return CompletableFuture.failedFuture(todo("delete", "007-5"));
    }

    // ---- admin ----

    @Override
    public CompletionStage<Void> ensureSchema(SchemaOptions options) {
        return CompletableFuture.failedFuture(todo("ensureSchema", "007-1 follow-up"));
    }

    @Override
    public CompletionStage<Void> rebuild(RebuildOptions options) {
        return CompletableFuture.failedFuture(todo("rebuild", "007-5"));
    }

    @Override
    public CompletionStage<IndexStatus> status() {
        return CompletableFuture.failedFuture(todo("status", "007-1 follow-up"));
    }

    // ---- Conformant ----

    @Override
    public String adapterName() {
        return "postgres";
    }

    @Override
    public String adapterVersion() {
        return "1.0.0";
    }

    @Override
    public ConformanceMatrix conformance() {
        String evidence = "org.synanton.synquest.postgres.PostgresSynquestEngineTest";
        return new ConformanceMatrix(
                adapterName(),
                adapterVersion(),
                List.of(
                        ConformanceEntry.unverified(
                                Capabilities.SYNQUEST_LEXICAL, "007-1 scaffold, lands in 007-2"),
                        ConformanceEntry.unverified(
                                Capabilities.SYNQUEST_VECTOR, "007-1 scaffold, lands in 007-3"),
                        ConformanceEntry.unverified(
                                Capabilities.SYNQUEST_HYBRID, "007-1 scaffold, lands in 007-5"),
                        ConformanceEntry.unverified(
                                Capabilities.SYNQUEST_FILTERS,
                                "007-1 scaffold, lands in 007-6")));
    }
}
