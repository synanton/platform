package org.synanton.synquest.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.EmbeddingRow;

/**
 * One-off benchmark utility: add bge-base (768d) embeddings to the existing
 * {@code demo-fixed} crawler corpus so the decoupled engine can be scored on the
 * same chunks/model as the recorded q25 run. Not a unit test; gated by
 * {@code -Dbench.reembed=true}. Requires the compose Cassandra and TEI on
 * 127.0.0.1:9042 / 127.0.0.1:30800.
 */
@EnabledIfSystemProperty(named = "bench.reembed", matches = "true")
class ReEmbedDemoFixedTest {

    private static final String TENANT = "demo-fixed";
    private static final String MODEL = "synanton-bge-base-embedding";
    private static final String TEI = "http://127.0.0.1:30800/v1/embeddings";
    private static final int BATCH = 16;

    @Test
    void reembed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpClient http = HttpClient.newHttpClient();
        try (CqlSession session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
                .withLocalDatacenter("datacenter1")
                .withKeyspace("ingestion_cache")
                .build()) {
            IngestionCacheClient cache = new IngestionCacheClient(session);
            int total = 0;
            for (var manifest : cache.listManifest(TENANT, 100)) {
                List<ChunkRow> chunks = cache.readChunks(TENANT, manifest.contentRefId());
                for (int offset = 0; offset < chunks.size(); offset += BATCH) {
                    List<ChunkRow> batch = chunks.subList(offset, Math.min(offset + BATCH, chunks.size()));
                    List<String> inputs = batch.stream().map(ChunkRow::chunkText).toList();
                    String body = mapper.writeValueAsString(Map.of("input", inputs, "model", MODEL));
                    HttpRequest request = HttpRequest.newBuilder(URI.create(TEI))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("TEI " + response.statusCode() + ": " + response.body());
                    }
                    var data = mapper.readTree(response.body()).path("data");
                    if (data.size() != batch.size()) {
                        throw new IllegalStateException("TEI returned " + data.size() + " for " + batch.size());
                    }
                    for (int index = 0; index < batch.size(); index++) {
                        var values = data.get(index).path("embedding");
                        float[] vector = new float[values.size()];
                        for (int k = 0; k < vector.length; k++) {
                            vector[k] = (float) values.get(k).asDouble();
                        }
                        ChunkRow chunk = batch.get(index);
                        cache.upsertEmbedding(new EmbeddingRow(TENANT, chunk.contentRefId(), chunk.chunkOrdinal(),
                                MODEL, chunk.chunkSha256(), vector, vector.length, Instant.now()));
                        total++;
                    }
                }
            }
            System.out.println("Re-embedded " + total + " chunks of " + TENANT + " with " + MODEL);
        }
    }
}
