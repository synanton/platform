package org.synanton.synquest.service;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.synanton.synquest.api.dto.IndexStats;
import org.synanton.synquest.api.dto.SearchRequest;
import org.synanton.synquest.api.dto.SearchResponse;
import org.synanton.synquest.config.SynquestProperties;
import org.synanton.synquest.engine.lucene.LuceneIndexBuilder;
import org.synanton.synquest.engine.lucene.LuceneSearchEngine;
import org.synanton.synquest.search.EmbeddedLuceneAdapter;
import org.synanton.synquest.search.SearchDocument;
import org.synanton.synquest.search.EntitySearchDocument;
import org.synanton.synquest.search.SearchEngineAdapter;
import org.synanton.synquest.search.SearchFilter;
import org.synanton.synquest.search.SearchIndexAdapter;
import org.synanton.synquest.search.SearchQuery;
import org.synanton.synquest.search.SearchResult;
import org.springframework.stereotype.Service;

/**
 * Facade over the pluggable search engine (#550). The service and API layers
 * never see the physical engine: they pass engine-neutral queries and receive
 * engine-neutral documents; Lucene lives in {@code engine.lucene}.
 */
@Service
public class SearchService {

    public enum Status { STARTING, READY, ERROR }

    private final SearchEngineAdapter engine;

    public SearchService(SearchIndexAdapter indexAdapter,
                         QueryEmbedder queryEmbedder,
                         SynquestProperties props) {
        this(indexAdapter, queryEmbedder, props, (SearchReranker) null,
                new org.synanton.synquest.config.SynquestRerankProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SearchService(SearchIndexAdapter indexAdapter,
                         QueryEmbedder queryEmbedder,
                         SynquestProperties props,
                         org.springframework.beans.factory.ObjectProvider<SearchReranker> reranker,
                         org.synanton.synquest.config.SynquestRerankProperties rerankProps) {
        this(indexAdapter, queryEmbedder, props, reranker.getIfAvailable(), rerankProps);
    }

    public SearchService(SearchIndexAdapter indexAdapter,
                         QueryEmbedder queryEmbedder,
                         SynquestProperties props,
                         SearchReranker reranker,
                         org.synanton.synquest.config.SynquestRerankProperties rerankProps) {
        this.engine = new LuceneSearchEngine(indexAdapter, queryEmbedder, props, reranker, rerankProps);
    }

    /** Test/back-compat: wraps the concrete Lucene builder behind the ports. */
    public SearchService(LuceneIndexBuilder builder,
                         QueryEmbedder queryEmbedder,
                         SynquestProperties props) {
        this(new EmbeddedLuceneAdapter(builder), queryEmbedder, props);
    }

    /** Test/back-compat: wraps the concrete Lucene builder behind the ports. */
    public SearchService(LuceneIndexBuilder builder,
                         QueryEmbedder queryEmbedder,
                         SynquestProperties props,
                         SearchReranker reranker,
                         org.synanton.synquest.config.SynquestRerankProperties rerankProps) {
        this(new EmbeddedLuceneAdapter(builder), queryEmbedder, props, reranker, rerankProps);
    }

    public void initTenant(String tenant) {
        engine.initTenant(tenant);
    }

    /** Engine-neutral candidate retrieval used by the resolution layer. */
    public SearchResult search(SearchQuery query, SearchFilter filter) throws IOException {
        return engine.search(query, filter);
    }

    public SearchResponse search(SearchRequest request) throws IOException {
        SearchQuery query = new SearchQuery(
                request.query(), request.topK(), request.topKDense(), request.topKLexical(), request.rrfK(),
                request.rerank(), request.rerankCandidates(), request.expand(), request.expandMaxChunks(),
                request.executionMode());
        SearchResult result = engine.search(query, new SearchFilter(request.tenant()));
        return new SearchResponse(
                result.documents().stream().map(SearchDocument::toHit).toList(),
                result.trace(), result.queryUsage());
    }

    public void reindex(String tenant) throws IOException {
        engine.reindex(tenant);
    }

    public int upsertEntities(String tenant, List<EntitySearchDocument> entities) throws IOException {
        return engine.upsertEntities(tenant, entities);
    }

    public void projectContentRefs(String tenant, Collection<UUID> contentRefs) throws IOException {
        engine.projectContentRefs(tenant, contentRefs);
    }

    public void deleteContentRefs(String tenant, Collection<UUID> contentRefs) throws IOException {
        engine.deleteContentRefs(tenant, contentRefs);
    }

    public IndexStats stats(String tenant) throws IOException {
        return engine.stats(tenant);
    }

    public Status getStatus() {
        return Status.valueOf(engine.statusName());
    }

    public boolean isReady(String tenant) {
        return engine.isReady(tenant);
    }
}
