package org.synanton.synquest.demo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Demo-only graph fixture lookup. Graph is visualization-only, never a retrieval leg. */
public final class DemoGraphService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path graphPath;
    private volatile Map<String, NeighborContainer> chunks;
    private volatile Set<String> knownChunks;

    public DemoGraphService(Path graphPath) {
        this.graphPath = Objects.requireNonNull(graphPath, "graphPath");
    }

    public GraphResponse neighbors(String chunkId) throws IOException {
        Map<String, NeighborContainer> data = chunks;
        if (data == null) {
            synchronized (this) {
                if (chunks == null) {
                    JsonNode root = JSON.readTree(Files.readString(graphPath));
                    knownChunks = JSON.convertValue(root.path("known_chunks"),
                            new TypeReference<Set<String>>() {});
                    chunks = JSON.convertValue(root.path("chunks"),
                            new TypeReference<Map<String, NeighborContainer>>() {});
                }
                data = chunks;
            }
        }
        NeighborContainer node = data.get(chunkId);
        if (node == null) {
            if (knownChunks != null && knownChunks.contains(chunkId)) {
                return new GraphResponse(chunkId, 0, List.of(), List.of(), List.of(), Map.of());
            }
            return null;
        }
        return new GraphResponse(chunkId, node.neighborCount(), node.entities(), node.neighborChunks(),
                node.neighbors(), node.byKind());
    }

    private record NeighborContainer(
            @JsonProperty("neighbor_count") int neighborCount,
            List<Entity> entities,
            @JsonProperty("neighbor_chunks") List<String> neighborChunks,
            List<Neighbor> neighbors,
            @JsonProperty("by_kind") Map<String, Integer> byKind) {}

    public record GraphResponse(
            @JsonProperty("chunk_id") String chunkId,
            @JsonProperty("neighbor_count") int neighborCount,
            List<Entity> entities,
            @JsonProperty("neighbor_chunks") List<String> neighborChunks,
            List<Neighbor> neighbors,
            @JsonProperty("by_kind") Map<String, Integer> byKind) {}

    public record Entity(
            @JsonProperty("canonical_form") String canonicalForm,
            String type,
            @JsonProperty("entity_kind") String entityKind,
            int df) {}

    public record Neighbor(
            @JsonProperty("chunk_id") String chunkId,
            List<Via> via) {}

    public record Via(
            @JsonProperty("canonical_form") String canonicalForm,
            String type,
            @JsonProperty("entity_kind") String entityKind,
            int df) {}
}
