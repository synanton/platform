package org.synanton.bench.baseline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.service.HybridSearcher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 028b.1b acceptance: wrapper delegates lexical/vector/hybrid legs to
 * production components on a mini index. Multi-tenant fan-out is 028b.3.
 */
class BaselineDelegationTest {

    private static final PrincipalRef PRINCIPAL = new PrincipalRef("benchmark", "028b");
    private static final PolicyContext POLICY = new PolicyContext("benchmark", "v1");

    private static Path miniIndex() throws Exception {
        Path dir = Files.createTempDirectory("baseline-mini");
        try (FSDirectory fs = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(fs, new IndexWriterConfig(new StandardAnalyzer()))) {
            addDoc(writer, "m1", "alpha beta gamma", new float[] {1, 0});
            addDoc(writer, "m2", "beta gamma delta", new float[] {0, 1});
            writer.commit();
        }
        return dir;
    }

    private static void addDoc(IndexWriter writer, String id, String text, float[] vec)
            throws Exception {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new TextField("text", text, Field.Store.YES));
        doc.add(new StoredField("text_stored", text));
        doc.add(
                new KnnFloatVectorField("embedding", vec, VectorSimilarityFunction.COSINE));
        writer.addDocument(doc);
    }

    private SecurityContext context(String tenant) {
        return SecurityContext.user(TenantScope.of(tenant), PRINCIPAL, POLICY);
    }

    @Test
    void delegatesAllThreeLegs() throws Exception {
        Path dir = miniIndex();
        TenantScope tenant = TenantScope.of("tenant_07");
        try (HybridSearcher searcher = new HybridSearcher(dir, 2)) {
            BaselineSynquestEngine engine =
                    new BaselineSynquestEngine(
                            new BaselineIndex.TenantIndex(Map.of("tenant_07", searcher)));

            var lex =
                    engine.search(
                                    context("tenant_07"),
                                    BaselineSynquestEngine.requestFor(
                                            "alpha", Optional.empty(), SearchMode.LEXICAL, tenant,
                                            Map.of(), 10))
                            .toCompletableFuture()
                            .join();
            assertThat(lex.hits()).isNotEmpty();

            var vec =
                    engine.search(
                                    context("tenant_07"),
                                    BaselineSynquestEngine.requestFor(
                                            "", Optional.of(new float[] {1, 0}), SearchMode.VECTOR,
                                            tenant, Map.of(), 10))
                            .toCompletableFuture()
                            .join();
            assertThat(vec.hits()).isNotEmpty();
            assertThat(vec.hits().get(0).chunkId().value()).isEqualTo("m1");

            var hyb =
                    engine.search(
                                    context("tenant_07"),
                                    BaselineSynquestEngine.requestFor(
                                            "alpha", Optional.of(new float[] {1, 0}),
                                            SearchMode.HYBRID, tenant, Map.of(), 10))
                            .toCompletableFuture()
                            .join();
            assertThat(hyb.hits()).isNotEmpty();
        }
    }
}
