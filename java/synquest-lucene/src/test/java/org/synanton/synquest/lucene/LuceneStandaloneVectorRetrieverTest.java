package org.synanton.synquest.lucene;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.storage.testkit.VectorRetrieverContract;
import org.synanton.synquest.api.VectorProjection;
import org.synanton.synquest.api.VectorRetriever;

/**
 * SYN-VECTOR-001 B3.3: standalone Lucene behind the {@link VectorRetrieverContract}.
 * File-backed index per test; no Cassandra storage involved.
 */
class LuceneStandaloneVectorRetrieverTest extends VectorRetrieverContract {

    @TempDir
    private Path root;

    private LuceneStandaloneVectorRetriever adapter;
    private Path indexDir;

    private LuceneStandaloneVectorRetriever adapter() {
        if (adapter == null) {
            indexDir = root.resolve("idx-" + System.nanoTime());
            adapter = new LuceneStandaloneVectorRetriever(indexDir, 2);
        }
        return adapter;
    }

    @Override
    protected VectorRetriever newRetriever() {
        return adapter();
    }

    @Override
    protected void seed(List<VectorProjection> projections) {
        adapter()
                .upsert(projections)
                .toCompletableFuture()
                .join();
    }
}
