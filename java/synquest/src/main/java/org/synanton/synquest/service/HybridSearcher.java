package org.synanton.synquest.service;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

public class HybridSearcher implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(HybridSearcher.class);

    private final SearcherManager searcherManager;
    private final int embeddingDim;

    public HybridSearcher(Path indexPath, int embeddingDim) throws IOException {
        FSDirectory dir = FSDirectory.open(indexPath);
        this.searcherManager = new SearcherManager(dir, null);
        this.embeddingDim = embeddingDim;
    }

    public TopDocs dense(float[] queryVec, int topK) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            searcher.setSimilarity(new BM25Similarity());
            KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery("embedding", queryVec, topK);
            return searcher.search(knnQuery, topK);
        } finally {
            searcherManager.release(searcher);
        }
    }

    public TopDocs lexical(String queryText, int topK) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            searcher.setSimilarity(new BM25Similarity());
            try {
                MultiFieldQueryParser parser = new MultiFieldQueryParser(
                        new String[]{"text"},
                        new org.apache.lucene.analysis.standard.StandardAnalyzer());
                Query query = parser.parse(MultiFieldQueryParser.escape(queryText));
                return searcher.search(query, topK);
            } catch (ParseException e) {
                log.warn("Query parse failed for '{}': {}", queryText, e.getMessage());
                return TopDocs.merge(0, new TopDocs[0]);
            }
        } finally {
            searcherManager.release(searcher);
        }
    }

    public StoredFields storedFields() throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.getIndexReader().storedFields();
        } finally {
            searcherManager.release(searcher);
        }
    }

    public int docCount() throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.getIndexReader().numDocs();
        } finally {
            searcherManager.release(searcher);
        }
    }

    public long generation() throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            return ((DirectoryReader) searcher.getIndexReader()).getVersion();
        } finally {
            searcherManager.release(searcher);
        }
    }

    public void refresh() throws IOException {
        searcherManager.maybeRefresh();
    }

    @Override
    public void close() throws IOException {
        searcherManager.close();
    }
}
