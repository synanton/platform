package org.synanton.synquest.inmemory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.contract.ConformanceEntry;
import org.synanton.storage.contract.ConformanceMatrix;
import org.synanton.storage.contract.GenerationId;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.StorageErrorKind;
import org.synanton.storage.contract.StorageException;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.api.IndexStatus;
import org.synanton.synquest.api.RebuildOptions;
import org.synanton.synquest.api.SchemaOptions;
import org.synanton.synquest.api.SearchCapabilities;
import org.synanton.synquest.api.SearchHit;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.SearchResult;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.SynquestIndexAdmin;
import org.synanton.synquest.api.SynquestIndexWriter;

/**
 * In-memory retrieval + projection-mutation port for tests. Brute-force scoring with
 * strict pre-ranking eligibility: a candidate is eligible only when its tenant matches
 * the caller's validated tenant scope (service contexts see all tenants).
 */
public class InMemorySynquestEngine implements SynquestEngine, SynquestIndexWriter, SynquestIndexAdmin, Conformant {

    private final ConcurrentHashMap<String, ProjectionEntry> projections = new ConcurrentHashMap<>();
    private volatile GenerationId activeGeneration = GenerationId.initial();

    private record ProjectionEntry(ChunkProjection projection) {}

    @Override
    public CompletionStage<Void> upsert(List<ChunkProjection> incoming) {
        for (ChunkProjection projection : incoming) {
            final ChunkProjection current = projection;
            projections.compute(
                    current.chunkId().value(),
                    (id, existing) -> {
                        if (existing != null
                                && current.orderingKey() <= existing.projection().orderingKey()) {
                            return existing;
                        }
                        return new ProjectionEntry(current);
                    });
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> delete(GenerationId generationId, Collection<ChunkId> ids) {
        for (ChunkId id : ids) {
            projections.computeIfPresent(
                    id.value(),
                    (key, existing) ->
                            existing.projection().generationId().equals(generationId) ? null : existing);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<SearchResult> search(SecurityContext context, SearchRequest request) {
        if (!request.temporal().isEmpty() && !capabilities().temporal()) {
            return CompletableFuture.failedFuture(
                    new StorageException(
                            StorageErrorKind.UNSUPPORTED,
                            "UNSUPPORTED: temporal retrieval not supported by this adapter"));
        }
        List<Scored> eligible = new ArrayList<>();
        for (ProjectionEntry entry : projections.values()) {
            ChunkProjection p = entry.projection();
            if (!eligibleTenant(context, p.tenantId())) {
                continue;
            }
            if (!matches(p.metadata(), request.filters().mustMatchMetadata())) {
                continue;
            }
            double lex = lexicalScore(p.text(), request.queryText());
            double vec =
                    request.queryEmbedding().map(q -> cosine(q, p.embedding())).orElse(0.0);
            double raw =
                    switch (request.mode()) {
                        case LEXICAL -> lex;
                        case VECTOR -> vec;
                        case HYBRID -> lex + vec;
                    };
            if (raw >= request.minScore()) {
                eligible.add(new Scored(p, lex, vec));
            }
        }
        double maxLexical = eligible.stream().mapToDouble(Scored::lexical).max().orElse(1.0);
        List<SearchHit> hits =
                eligible.stream()
                        .map(s -> Map.entry(s, combined(s, request, maxLexical)))
                        .sorted(
                                Comparator.<Map.Entry<Scored, Double>>comparingDouble(Map.Entry::getValue)
                                        .reversed()
                                        .thenComparing(e -> e.getKey().projection().chunkId().value()))
                        .limit(request.topK())
                        .map(e -> toHit(e.getKey().projection(), e.getValue(), request.queryText()))
                        .toList();
        Map<org.synanton.storage.contract.ChunkId, String> highlights = new HashMap<>();
        for (SearchHit hit : hits) {
            highlights.put(hit.chunkId(), snippet(hit.text(), request.queryText()));
        }
        return CompletableFuture.completedFuture(
                new SearchResult(hits, eligible.size(), highlights));
    }

    @Override
    public SearchCapabilities capabilities() {
        return SearchCapabilities.pocBaseline();
    }

    @Override
    public String adapterName() {
        return "inmemory";
    }

    @Override
    public String adapterVersion() {
        return "1.0.0";
    }

    @Override
    public ConformanceMatrix conformance() {
        String evidence = "org.synanton.synquest.inmemory.InMemorySynquestEngineTest";
        return new ConformanceMatrix(
                adapterName(),
                adapterVersion(),
                List.of(
                        ConformanceEntry.supported(Capabilities.SYNQUEST_LEXICAL, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_VECTOR, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_HYBRID, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_FILTERS, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_HIGHLIGHTS, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_ELIGIBILITY, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_TEMPORAL_REJECTION, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_ORDERING, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_GENERATION_DELETE, evidence)));
    }

    @Override
    public CompletionStage<Void> ensureSchema(SchemaOptions options) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> rebuild(RebuildOptions options) {
        activeGeneration = options.targetGeneration();
        if (options.full()) {
            projections.clear();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<IndexStatus> status() {
        return CompletableFuture.completedFuture(
                new IndexStatus(activeGeneration, projections.size(), true));
    }

    private record Scored(ChunkProjection projection, double lexical, double vector) {}

    private double combined(Scored s, SearchRequest request, double maxLexical) {
        return switch (request.mode()) {
            case LEXICAL -> s.lexical();
            case VECTOR -> s.vector();
            case HYBRID -> s.lexical() / Math.max(maxLexical, 1e-9) + s.vector();
        };
    }

    private static boolean eligibleTenant(SecurityContext context, String candidateTenant) {
        return context.service() || context.tenantScope().tenantId().equals(candidateTenant);
    }

    private static boolean matches(Map<String, String> metadata, Map<String, String> required) {
        for (Map.Entry<String, String> entry : required.entrySet()) {
            if (!entry.getValue().equals(metadata.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static double lexicalScore(String text, String query) {
        var queryTokens = tokens(query);
        if (queryTokens.isEmpty()) {
            return 0.0;
        }
        var textTokens = new java.util.HashSet<>(tokens(text));
        return queryTokens.stream().filter(textTokens::contains).count();
    }

    private static List<String> tokens(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static double cosine(float[] query, float[] doc) {
        if (query == null || doc == null || query.length == 0 || query.length != doc.length) {
            return 0.0;
        }
        double dot = 0.0, nq = 0.0, nd = 0.0;
        for (int i = 0; i < query.length; i++) {
            dot += query[i] * doc[i];
            nq += query[i] * query[i];
            nd += doc[i] * doc[i];
        }
        if (nq == 0.0 || nd == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(nq) * Math.sqrt(nd));
    }

    private static SearchHit toHit(ChunkProjection p, double score, String queryText) {
        return new SearchHit(p.chunkId(), p.documentId(), score, p.text(), p.metadata());
    }

    private static String snippet(String text, String query) {
        String lower = text.toLowerCase();
        int best = -1;
        for (String token : tokens(query)) {
            int at = lower.indexOf(token);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        if (best < 0) {
            return text.length() <= 120 ? text : text.substring(0, 120);
        }
        int from = Math.max(0, best - 30);
        int to = Math.min(text.length(), best + 90);
        return (from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : "");
    }
}
