package org.synanton.synquest.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * YDB-POC-006 preliminary operating points. Runs ONLY with
 * {@code -Dydb.bench=true} (excluded from PR runs): builds a seeded synthetic
 * index with the production schema/analyzer/similarity, then drives the
 * production {@link HybridSearcher} + {@link RrfFusion} exactly as
 * {@code SearchService} does (lexical/dense top 100, RRF k=60, topK 20).
 *
 * <p>Honest scope: lexical recall is real (planted relevance); vector-leg numbers
 * are LATENCY ONLY (synthetic random vectors carry no relevance). Absolute
 * agreed thresholds come from the v1-corpus run (006 follow-up); the numbers
 * printed here are preliminary operating points, not the baseline.
 */
@EnabledIfSystemProperty(named = "ydb.bench", matches = "true")
class BaselineBench {

    private static final int DOCS = Integer.getInteger("ydb.bench.docs", 2_000);
    private static final int CHUNKS_PER_DOC = Integer.getInteger("ydb.bench.chunks", 8);
    private static final int DIM = 384;
    private static final int QUERIES = 40;
    private static final int REPS = 5;
    private static final int TOP_LEX = 100;
    private static final int TOP_DENSE = 100;
    private static final int RRF_K = 60;
    private static final int TOP_K = 20;

    @Test
    void measureOperatingPoints() throws Exception {
        Random rnd = new Random(42);
        List<String> vocab = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            vocab.add("tok" + i);
        }
        // Golden queries: 3 distinctive terms each, planted into 5 docs.
        List<List<String>> golden = new ArrayList<>();
        Map<Integer, Set<String>> relevant = new HashMap<>();
        for (int q = 0; q < QUERIES; q++) {
            List<String> terms = List.of("gold" + q + "a", "gold" + q + "b", "gold" + q + "c");
            golden.add(terms);
            relevant.put(q, new HashSet<>());
        }

        Path path = Files.createTempDirectory("bench-idx");
        try (FSDirectory dir = FSDirectory.open(path);
                IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
            for (int d = 0; d < DOCS; d++) {
                for (int c = 0; c < CHUNKS_PER_DOC; c++) {
                    StringBuilder text = new StringBuilder();
                    int len = 80 + rnd.nextInt(120);
                    for (int t = 0; t < len; t++) {
                        text.append(vocab.get(rnd.nextInt(vocab.size()))).append(' ');
                    }
                    int q = (d * CHUNKS_PER_DOC + c) % QUERIES;
                    if (relevant.get(q).size() < 5 && rnd.nextDouble() < 0.05) {
                        for (String term : golden.get(q)) {
                            text.append(term).append(' ');
                        }
                        relevant.get(q).add(d + "#" + c);
                    }
                    Document doc = new Document();
                    doc.add(new StringField("id", d + "#" + c, Field.Store.YES));
                    doc.add(new TextField("text", text.toString(), Field.Store.YES));
                    doc.add(new KnnFloatVectorField("embedding", randomVec(rnd), VectorSimilarityFunction.COSINE));
                    writer.addDocument(doc);
                }
            }
            writer.commit();
        }
        for (Set<String> rel : relevant.values()) {
            if (rel.size() < 5) {
                throw new IllegalStateException("planting failed; adjust seed/fractions");
            }
        }

        HybridSearcher searcher = new HybridSearcher(path, DIM);
        // Mirror SearchService: dense + lexical run concurrently, fusion after both join.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<Double> lexLat = new ArrayList<>();
        List<Double> vecLat = new ArrayList<>();
        List<Double> hybLat = new ArrayList<>();
        double recallSum = 0.0;
        float[] queryVec = randomVec(new Random(7));
        for (int q = 0; q < QUERIES; q++) {
            String query = String.join(" ", golden.get(q));
            searcher.refresh();
            long t0 = System.nanoTime();
            TopDocs lex = null;
            for (int r = 0; r < REPS; r++) {
                long s = System.nanoTime();
                lex = searcher.lexical(query, TOP_LEX);
                lexLat.add((System.nanoTime() - s) / 1_000_000.0);
            }
            for (int r = 0; r < REPS; r++) {
                long s = System.nanoTime();
                searcher.dense(queryVec, TOP_DENSE);
                vecLat.add((System.nanoTime() - s) / 1_000_000.0);
            }
            TopDocs dense = searcher.dense(queryVec, TOP_DENSE);
            // Full hybrid pipeline as one timed unit: concurrent legs + fusion
            // (mirrors SearchService; previously only combine() was timed — see 006).
            for (int r = 0; r < REPS; r++) {
                long hs = System.nanoTime();
                var denseFuture = pool.submit(() -> searcher.dense(queryVec, TOP_DENSE));
                var lexFuture = pool.submit(() -> searcher.lexical(query, TOP_LEX));
                RrfFusion.combine(denseFuture.get(), lexFuture.get(), TOP_K, RRF_K);
                hybLat.add((System.nanoTime() - hs) / 1_000_000.0);
            }

            // Lexical Recall@10 against planted relevance.
            var stored = searcher.storedFields();
            Set<String> retrieved = new HashSet<>();
            try (var reader = DirectoryReader.open(FSDirectory.open(path))) {
                var searcher2 = new org.apache.lucene.search.IndexSearcher(reader);
                var top = searcher2.search(
                        new org.apache.lucene.queryparser.classic.MultiFieldQueryParser(
                                        new String[] {"text"}, new StandardAnalyzer())
                                .parse(org.apache.lucene.queryparser.classic.MultiFieldQueryParser.escape(query)),
                        10);
                for (var sd : top.scoreDocs) {
                    retrieved.add(reader.storedFields().document(sd.doc).get("id"));
                }
            }
            Set<String> rel = relevant.get(q);
            long hit = retrieved.stream().filter(rel::contains).count();
            recallSum += (double) hit / rel.size();
        }
        pool.shutdown();
        searcher.close();

        System.out.println("BENCH lex_ms_p50=" + p50(lexLat) + " lex_ms_p95=" + p95(lexLat)
                + " vec_ms_p50=" + p50(vecLat) + " vec_ms_p95=" + p95(vecLat)
                + " hyb_ms_p50=" + p50(hybLat) + " hyb_ms_p95=" + p95(hybLat)
                + " lex_recall10=" + String.format("%.3f", recallSum / QUERIES)
                + " chunks=" + (DOCS * CHUNKS_PER_DOC));
    }

    private static float[] randomVec(Random rnd) {
        float[] v = new float[DIM];
        double norm = 0;
        for (int i = 0; i < DIM; i++) {
            v[i] = rnd.nextFloat() * 2 - 1;
            norm += v[i] * v[i];
        }
        norm = Math.sqrt(norm);
        float scale = (float) norm;
        for (int i = 0; i < DIM; i++) {
            v[i] /= scale;
        }
        return v;
    }

    private static double p50(List<Double> xs) {
        return pct(xs, 50);
    }

    private static double p95(List<Double> xs) {
        return pct(xs, 95);
    }

    private static double pct(List<Double> xs, int p) {
        List<Double> sorted = xs.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * p / 100.0)));
    }
}
