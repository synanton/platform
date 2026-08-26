package org.synanton.relix.adapter.out.graph.neo4j;

import org.jgrapht.graph.DirectedMultigraph;
import org.synanton.relix.api.dto.Edge;
import org.synanton.relix.api.dto.Entity;
import org.synanton.relix.api.dto.GraphStats;
import org.synanton.relix.api.dto.Path;
import org.synanton.relix.api.dto.SourceRef;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.graph.GraphEdge;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.GraphNode;
import org.synanton.relix.graph.SourceRefCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bolt/Cypher adapter. Relationship type is always {@code REL}; the original verb is a property (avoids Cypher injection).
 */
public class Neo4jGraphConnector implements GraphConnector {

    private static final int MAX_HOPS_CAP = 6;

    private final GraphLoader graphLoader;
    private final CypherExecutor cypher;
    private final Map<String, GraphStats> statsByTenant = new ConcurrentHashMap<>();

    public Neo4jGraphConnector(GraphLoader graphLoader, CypherExecutor cypher) {
        this.graphLoader = graphLoader;
        this.cypher = cypher;
    }

    @Override
    public String id() {
        return "neo4j";
    }

    @Override
    public EngineDescriptor descriptor() {
        return new EngineDescriptor("neo4j", "neo4j-bolt", "ALL_NATIVE");
    }

    @Override
    public void loadTenant(String tenant) {
        long start = System.currentTimeMillis();
        GraphLoader.LoadResult loaded = graphLoader.load(tenant);
        DirectedMultigraph<GraphNode, GraphEdge> graph = loaded.graph();

        cypher.write("MATCH (n:Entity {tenant: $tenant}) DETACH DELETE n", Map.of("tenant", tenant));

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (GraphNode node : graph.vertexSet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", node.entityId().toString());
            row.put("label", node.label());
            row.put("type", node.type());
            row.put("confidence", node.confidence());
            row.put("sources", SourceRefCodec.encode(node.sourceRefs()));
            nodes.add(row);
        }
        if (!nodes.isEmpty()) {
            cypher.write("""
                    UNWIND $nodes AS row
                    MERGE (n:Entity {id: row.id, tenant: $tenant})
                    SET n.label = row.label, n.type = row.type, n.confidence = row.confidence, n.sources = row.sources
                    """, Map.of("tenant", tenant, "nodes", nodes));
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        for (GraphEdge edge : graph.edgeSet()) {
            GraphNode source = graph.getEdgeSource(edge);
            GraphNode target = graph.getEdgeTarget(edge);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", edge.edgeId().toString());
            row.put("fromId", source.entityId().toString());
            row.put("toId", target.entityId().toString());
            row.put("verb", edge.verb());
            row.put("confidence", edge.confidence());
            row.put("sources", SourceRefCodec.encode(edge.sourceRefs()));
            edges.add(row);
        }
        if (!edges.isEmpty()) {
            cypher.write("""
                    UNWIND $edges AS row
                    MATCH (a:Entity {id: row.fromId, tenant: $tenant})
                    MATCH (b:Entity {id: row.toId, tenant: $tenant})
                    MERGE (a)-[r:REL {id: row.id}]->(b)
                    SET r.verb = row.verb, r.confidence = row.confidence, r.sources = row.sources
                    """, Map.of("tenant", tenant, "edges", edges));
        }

        long loadTimeMs = System.currentTimeMillis() - start;
        statsByTenant.put(tenant, new GraphStats(tenant, nodes.size(), edges.size(), loadTimeMs, loaded.generation()));
    }

    @Override
    public GraphStats stats(String tenant) {
        return statsByTenant.getOrDefault(tenant, new GraphStats(tenant, 0, 0, 0, -1));
    }

    @Override
    public EntityLookupResult lookupEntities(String tenant, String label, String type, int limit) {
        if (label == null || label.isBlank()) {
            return new EntityLookupResult(List.of(), 0);
        }
        String typeFilter = type == null ? "" : type;
        List<Map<String, Object>> rows = cypher.read("""
                MATCH (n:Entity {tenant: $tenant})
                WHERE toLower(n.label) = toLower($label)
                  AND ($type = '' OR toLower(n.type) = toLower($type))
                RETURN n.id AS id, n.label AS label, n.type AS type, n.confidence AS confidence, n.sources AS sources
                """, Map.of("tenant", tenant, "label", label, "type", typeFilter));
        int candidateCount = rows.size();
        List<Entity> entities = rows.stream().limit(limit).map(Neo4jGraphConnector::toEntity).toList();
        return new EntityLookupResult(entities, candidateCount);
    }

    @Override
    public OneHopResult oneHop(String tenant, UUID entityId, List<String> edgeTypes, String direction, int limit) {
        if (entityId == null) {
            return new OneHopResult(List.of(), List.of(), 0);
        }
        String resolvedDirection = direction == null ? "OUT" : direction;
        List<String> verbs = edgeTypes == null ? List.of() : edgeTypes;
        List<Map<String, Object>> rows = new ArrayList<>();
        if ("OUT".equalsIgnoreCase(resolvedDirection) || "BOTH".equalsIgnoreCase(resolvedDirection)) {
            rows.addAll(cypher.read("""
                    MATCH (c:Entity {id: $id, tenant: $tenant})-[r:REL]->(o:Entity {tenant: $tenant})
                    WHERE size($verbs) = 0 OR r.verb IN $verbs
                    RETURN r.id AS edgeId, r.verb AS verb, r.confidence AS confidence, r.sources AS sources,
                           c.id AS fromId, o.id AS toId,
                           o.id AS nid, o.label AS nlabel, o.type AS ntype, o.confidence AS nconfidence, o.sources AS nsources
                    """, Map.of("id", entityId.toString(), "tenant", tenant, "verbs", verbs)));
        }
        if ("IN".equalsIgnoreCase(resolvedDirection) || "BOTH".equalsIgnoreCase(resolvedDirection)) {
            rows.addAll(cypher.read("""
                    MATCH (o:Entity {tenant: $tenant})-[r:REL]->(c:Entity {id: $id, tenant: $tenant})
                    WHERE size($verbs) = 0 OR r.verb IN $verbs
                    RETURN r.id AS edgeId, r.verb AS verb, r.confidence AS confidence, r.sources AS sources,
                           o.id AS fromId, c.id AS toId,
                           o.id AS nid, o.label AS nlabel, o.type AS ntype, o.confidence AS nconfidence, o.sources AS nsources
                    """, Map.of("id", entityId.toString(), "tenant", tenant, "verbs", verbs)));
        }
        int candidateCount = rows.size();
        List<Edge> edges = new ArrayList<>();
        Map<UUID, Entity> neighbours = new LinkedHashMap<>();
        rows.stream().limit(limit).forEach(row -> {
            edges.add(toEdge(row));
            Entity neighbour = new Entity(
                    UUID.fromString(String.valueOf(row.get("nid"))),
                    String.valueOf(row.get("nlabel")),
                    String.valueOf(row.get("ntype")),
                    toDouble(row.get("nconfidence")),
                    SourceRefCodec.decode(stringOrEmpty(row.get("nsources"))));
            neighbours.put(neighbour.entityId(), neighbour);
        });
        return new OneHopResult(new ArrayList<>(neighbours.values()), edges, candidateCount);
    }

    @Override
    public KHopResult kHopPaths(String tenant, UUID fromEntityId, UUID toEntityId, int maxHops, int maxPaths) {
        if (fromEntityId == null || toEntityId == null) {
            return new KHopResult(List.of(), List.of(), List.of(), 0);
        }
        int hops = Math.min(Math.max(maxHops, 1), MAX_HOPS_CAP);
        String cypherText = """
                MATCH p = (a:Entity {id: $fromId, tenant: $tenant})-[:REL*1..%d]->(b:Entity {id: $toId, tenant: $tenant})
                RETURN [n IN nodes(p) | {id: n.id, label: n.label, type: n.type, confidence: n.confidence, sources: n.sources}] AS nodes,
                       [r IN relationships(p) | {id: r.id, verb: r.verb, confidence: r.confidence, sources: r.sources,
                         fromId: startNode(r).id, toId: endNode(r).id}] AS rels
                LIMIT $maxPaths
                """.formatted(hops);
        List<Map<String, Object>> rows = cypher.read(cypherText, Map.of(
                "fromId", fromEntityId.toString(),
                "toId", toEntityId.toString(),
                "tenant", tenant,
                "maxPaths", maxPaths));

        Map<UUID, Entity> entities = new LinkedHashMap<>();
        Map<UUID, Edge> edges = new LinkedHashMap<>();
        List<Path> paths = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<Map<String, Object>> pathNodes = castMapList(row.get("nodes"));
            List<Map<String, Object>> pathRels = castMapList(row.get("rels"));
            List<String> hopIds = new ArrayList<>();
            double score = 1.0;
            for (int index = 0; index < pathNodes.size(); index++) {
                Entity entity = toEntity(pathNodes.get(index));
                entities.put(entity.entityId(), entity);
                hopIds.add(entity.entityId().toString());
                if (index < pathRels.size()) {
                    Edge edge = toEdge(pathRels.get(index));
                    edges.put(edge.edgeId(), edge);
                    hopIds.add(edge.edgeId().toString());
                    score *= edge.confidence();
                }
            }
            paths.add(new Path(hopIds, score));
        }
        return new KHopResult(new ArrayList<>(entities.values()), new ArrayList<>(edges.values()), paths, rows.size());
    }

    private static Entity toEntity(Map<String, Object> row) {
        return new Entity(
                UUID.fromString(String.valueOf(row.get("id"))),
                String.valueOf(row.get("label")),
                String.valueOf(row.get("type")),
                toDouble(row.get("confidence")),
                SourceRefCodec.decode(stringOrEmpty(row.get("sources"))));
    }

    private static Edge toEdge(Map<String, Object> row) {
        List<SourceRef> refs = SourceRefCodec.decode(stringOrEmpty(row.get("sources")));
        return new Edge(
                UUID.fromString(String.valueOf(row.get("edgeId") != null ? row.get("edgeId") : row.get("id"))),
                UUID.fromString(String.valueOf(row.get("fromId"))),
                UUID.fromString(String.valueOf(row.get("toId"))),
                String.valueOf(row.get("verb")),
                toDouble(row.get("confidence")),
                refs);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMapList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> maps = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    maps.add((Map<String, Object>) map);
                }
            }
            return maps;
        }
        return List.of();
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null ? 0.0 : Double.parseDouble(value.toString());
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }
}
