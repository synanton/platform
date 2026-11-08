package org.synanton.synquest.search;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.synanton.synquest.engine.lucene.LuceneIndexBuilder;

/**
 * Embedded Lucene implementation of {@link SearchIndexAdapter} (#547).
 * All Lucene writer/indexing details stay behind this class; the search path
 * remains in {@code SearchService} today and moves here in the follow-up
 * recorded by ADR-020.
 */
@Component
public final class EmbeddedLuceneAdapter implements SearchIndexAdapter {

    private final LuceneIndexBuilder builder;

    public EmbeddedLuceneAdapter(LuceneIndexBuilder builder) {
        this.builder = builder;
    }

    @Override
    public void rebuild(String tenant) throws IOException {
        builder.build(tenant);
    }

    @Override
    public int indexContentRefs(String tenant, Collection<UUID> contentRefs) throws IOException {
        return builder.upsertContentRefs(tenant, contentRefs);
    }

    @Override
    public int deleteChunkIds(String tenant, Collection<String> chunkIds) throws IOException {
        return builder.deleteChunkIds(tenant, chunkIds);
    }

    @Override
    public int deleteContentRefs(String tenant, Collection<UUID> contentRefs) throws IOException {
        return builder.deleteContentRefs(tenant, contentRefs);
    }

    @Override
    public boolean isEmpty(String tenant) {
        return builder.isEmpty(tenant);
    }

    @Override
    public Path indexPath(String tenant) {
        return builder.indexPath(tenant);
    }

    @Override
    public Optional<IndexReport> lastReport(String tenant) {
        return builder.lastReport(tenant).map(report -> new IndexReport(
                report.embeddingModel(), report.embeddingDim(), report.truncated(), report.docs(),
                report.vectorDocs(), report.dimMismatches(), report.missingVectors(), report.sectionDocs()));
    }
}
