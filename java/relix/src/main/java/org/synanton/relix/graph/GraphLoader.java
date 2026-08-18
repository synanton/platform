package org.synanton.relix.graph;

import org.jgrapht.graph.DirectedMultigraph;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.AnalysisRow;
import org.synanton.relix.index.EntityIndex;
import org.synanton.relix.loader.Pass2AnalysisParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GraphLoader {

    private static final Logger log = LoggerFactory.getLogger(GraphLoader.class);

    private final IngestionCacheClient cacheClient;
    private final Pass2AnalysisParser parser;
    private final EntityCanonicalizer canonicalizer;

    public GraphLoader(IngestionCacheClient cacheClient,
                       Pass2AnalysisParser parser,
                       EntityCanonicalizer canonicalizer) {
        this.cacheClient = cacheClient;
        this.parser = parser;
        this.canonicalizer = canonicalizer;
    }

    public record LoadResult(
            DirectedMultigraph<GraphNode, GraphEdge> graph,
            EntityIndex entityIndex,
            Map<UUID, GraphNode> nodeById,
            long loadTimeMs,
            long generation
    ) {}

    public LoadResult load(String tenant) {
        long start = System.currentTimeMillis();

        DirectedMultigraph<GraphNode, GraphEdge> graph =
                new DirectedMultigraph<>(GraphEdge.class);
        Map<UUID, GraphNode> nodeById = new LinkedHashMap<>();
        EntityIndex entityIndex = new EntityIndex();
        int errorCount = 0;

        List<org.synanton.ingestioncache.domain.ManifestRow> manifests =
                cacheClient.listManifest(tenant, 200_000);

        for (var manifest : manifests) {
            List<AnalysisRow> analysisRows = cacheClient.listAnalysis(tenant, manifest.contentRefId());
            for (AnalysisRow row : analysisRows) {
                if (row.passNumber() != 2) continue;
                Pass2AnalysisParser.Pass2Result result =
                        parser.parse(row.contentRefId(), row.chunkOrdinal(), row.analysisJson());

                // Canonicalise and merge entities
                Map<String, GraphNode> localEntities = new HashMap<>();
                for (var typedEntity : result.entities()) {
                    UUID id = canonicalizer.entityId(tenant, typedEntity.type(), typedEntity.label());
                    GraphNode node = nodeById.computeIfAbsent(id, k -> {
                        GraphNode n = new GraphNode(id, typedEntity.label(), typedEntity.type(), typedEntity.confidence());
                        graph.addVertex(n);
                        entityIndex.add(n);
                        return n;
                    });
                    node.mergeConfidence(typedEntity.confidence());
                    node.mergeSourceRef(row.contentRefId(), typedEntity.chunkOrdinals());
                    localEntities.put(canonicalizer.normalise(typedEntity.label()) + "|" + typedEntity.type(), node);
                }

                // Add relations
                for (var rel : result.relations()) {
                    String fromKey = canonicalizer.normalise(rel.from()) + "|Unknown";
                    String toKey = canonicalizer.normalise(rel.to()) + "|Unknown";
                    // Try to find matching node by label (type-agnostic fallback)
                    GraphNode fromNode = findNodeByLabel(nodeById, tenant, rel.from(), localEntities);
                    GraphNode toNode = findNodeByLabel(nodeById, tenant, rel.to(), localEntities);
                    if (fromNode == null || toNode == null) continue;

                    UUID edgeId = canonicalizer.edgeId(tenant, fromNode.entityId(), rel.verb(), toNode.entityId());
                    // Check if this edge already exists
                    GraphEdge existing = findEdge(graph, fromNode, toNode, rel.verb());
                    if (existing != null) {
                        existing.mergeConfidence(rel.confidence());
                        existing.mergeSourceRef(row.contentRefId(), rel.chunkOrdinals());
                    } else {
                        GraphEdge edge = new GraphEdge(edgeId, rel.verb(), rel.confidence());
                        edge.mergeSourceRef(row.contentRefId(), rel.chunkOrdinals());
                        graph.addEdge(fromNode, toNode, edge);
                    }
                }
            }
        }

        long loadTimeMs = System.currentTimeMillis() - start;
        long generation = System.currentTimeMillis();
        log.info("Loaded graph for tenant '{}': {} entities, {} edges in {}ms",
                tenant, graph.vertexSet().size(), graph.edgeSet().size(), loadTimeMs);

        return new LoadResult(graph, entityIndex, nodeById, loadTimeMs, generation);
    }

    private GraphNode findNodeByLabel(Map<UUID, GraphNode> nodeById, String tenant, String label,
                                       Map<String, GraphNode> localEntities) {
        // Try exact match in local context first (type-specific)
        for (var entry : localEntities.entrySet()) {
            if (entry.getKey().startsWith(canonicalizer.normalise(label) + "|")) {
                return entry.getValue();
            }
        }
        // Fallback: try "Unknown" type
        UUID id = canonicalizer.entityId(tenant, "Unknown", label);
        return nodeById.get(id);
    }

    private GraphEdge findEdge(DirectedMultigraph<GraphNode, GraphEdge> graph,
                                GraphNode from, GraphNode to, String verb) {
        if (!graph.containsVertex(from) || !graph.containsVertex(to)) return null;
        for (GraphEdge e : graph.getAllEdges(from, to)) {
            if (verb.equals(e.verb())) return e;
        }
        return null;
    }
}
