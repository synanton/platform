package org.synanton.relix.adapter.out.graph.nebula;

import org.jgrapht.graph.DirectedMultigraph;
import org.synanton.relix.api.dto.Edge;
import org.synanton.relix.api.dto.Entity;
import org.synanton.relix.api.dto.GraphStats;
import org.synanton.relix.api.dto.Path;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.graph.GraphEdge;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.GraphNode;
import org.synanton.relix.graph.SourceRefCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * nGQL adapter. Schema assumed: tag {@code entity}, edge type {@code rel}. Space is selected per statement.
 */
public class NebulaGraphConnector implements GraphConnector {

    private static final int MAX_HOPS_CAP = 6;

    private final GraphLoader graphLoader;
    private final NebulaSession session;
    private final String space;
    private final Map<String, GraphStats> statsByTenant = new ConcurrentHashMap<>();

    public NebulaGraphConnector(GraphLoader graphLoader, NebulaSession session, String space) {
        this.graphLoader = graphLoader;
        this.session = session;
        this.space = space == null || space.isBlank() ? "synanton" : space;
    }

    @Override
    public String id() {
        return "nebula";
    }

    @Override
    public EngineDescriptor descriptor() {
        return new EngineDescriptor("nebula", "nebula-ngql", "ALL_NATIVE");
    }

    @Override
    public void loadTenant(String tenant) {
        long start = System.currentTimeMillis();
        GraphLoader.LoadResult loaded = graphLoader.load(tenant);
        DirectedMultigraph<GraphNode, GraphEdge> graph = loaded.graph();
        session.execute("USE `" + escapeIdent(space) + "`");
        int nodeCount = 0;
        for (GraphNode node : graph.vertexSet()) {
            session.execute(insertVertex(tenant, node));
            nodeCount++;
        }
        int edgeCount = 0;
        for (GraphEdge edge : graph.edgeSet()) {
            GraphNode source = graph.getEdgeSource(edge);
            GraphNode target = graph.getEdgeTarget(edge);
            session.execute(insertEdge(tenant, source, target, edge));
            edgeCount++;
        }
        long loadTimeMs = System.currentTimeMillis() - start;
        statsByTenant.put(tenant, new GraphStats(tenant, nodeCount, edgeCount, loadTimeMs, loaded.generation()));
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
        StringBuilder ngql = new StringBuilder();
        ngql.append("USE `").append(escapeIdent(space)).append("`; ");
        ngql.append("LOOKUP ON entity WHERE entity.tenant == \"").append(escape(tenant)).append("\" ");
        ngql.append("AND entity.label == \"").append(escape(label)).append("\" ");
        if (type != null && !type.isBlank()) {
            ngql.append("AND entity.type == \"").append(escape(type)).append("\" ");
        }
        ngql.append("YIELD entity.id AS id, entity.label AS label, entity.type AS type, ");
        ngql.append("entity.confidence AS confidence, entity.sources AS sources");
        List<Map<String, Object>> rows = session.query(ngql.toString());
        List<Entity> entities = rows.stream().limit(limit).map(NebulaGraphConnector::toEntity).toList();
        return new EntityLookupResult(entities, rows.size());
    }

    @Override
    public OneHopResult oneHop(String tenant, UUID entityId, List<String> edgeTypes, String direction, int limit) {
        if (entityId == null) {
            return new OneHopResult(List.of(), List.of(), 0);
        }
        String resolvedDirection = direction == null ? "OUT" : direction;
        StringBuilder ngql = new StringBuilder();
        ngql.append("USE `").append(escapeIdent(space)).append("`; ");
        ngql.append("GO FROM \"").append(escape(entityId.toString())).append("\" OVER rel ");
        if ("IN".equalsIgnoreCase(resolvedDirection)) {
            ngql.append("REVERSELY ");
        } else if ("BOTH".equalsIgnoreCase(resolvedDirection)) {
            ngql.append("BIDIRECT ");
        }
        ngql.append("WHERE $$.entity.tenant == \"").append(escape(tenant)).append("\" ");
        if (edgeTypes != null && !edgeTypes.isEmpty()) {
            ngql.append("AND properties(edge).verb IN [");
            for (int index = 0; index < edgeTypes.size(); index++) {
                if (index > 0) {
                    ngql.append(", ");
                }
                ngql.append('"').append(escape(edgeTypes.get(index))).append('"');
            }
            ngql.append("] ");
        }
        ngql.append("YIELD properties(edge).id AS edgeId, properties(edge).verb AS verb, ");
        ngql.append("properties(edge).confidence AS confidence, properties(edge).sources AS sources, ");
        ngql.append("src(edge) AS fromId, dst(edge) AS toId, ");
        ngql.append("$$.entity.id AS nid, $$.entity.label AS nlabel, $$.entity.type AS ntype, ");
        ngql.append("$$.entity.confidence AS nconfidence, $$.entity.sources AS nsources");
        List<Map<String, Object>> rows = session.query(ngql.toString());
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
        return new OneHopResult(new ArrayList<>(neighbours.values()), edges, rows.size());
    }

    @Override
    public KHopResult kHopPaths(String tenant, UUID fromEntityId, UUID toEntityId, int maxHops, int maxPaths) {
        if (fromEntityId == null || toEntityId == null) {
            return new KHopResult(List.of(), List.of(), List.of(), 0);
        }
        int hops = Math.min(Math.max(maxHops, 1), MAX_HOPS_CAP);
        String ngql = "USE `" + escapeIdent(space) + "`; "
                + "FIND " + hops + " TO " + hops + " STEPS PATH FROM \""
                + escape(fromEntityId.toString()) + "\" TO \"" + escape(toEntityId.toString())
                + "\" OVER rel YIELD path AS p | LIMIT " + maxPaths;
        List<Map<String, Object>> rows = session.query(ngql);
        List<Path> paths = new ArrayList<>();
        Map<UUID, Entity> entities = new LinkedHashMap<>();
        Map<UUID, Edge> edges = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object hopsObj = row.get("hops");
            if (hopsObj instanceof List<?> hopList) {
                List<String> hopIds = hopList.stream().map(String::valueOf).toList();
                paths.add(new Path(hopIds, toDouble(row.get("score"))));
            }
            if (row.get("entities") instanceof List<?> entityRows) {
                for (Object item : entityRows) {
                    if (item instanceof Map<?, ?> map) {
                        Entity entity = toEntity(cast(map));
                        entities.put(entity.entityId(), entity);
                    }
                }
            }
            if (row.get("edges") instanceof List<?> edgeRows) {
                for (Object item : edgeRows) {
                    if (item instanceof Map<?, ?> map) {
                        Edge edge = toEdge(cast(map));
                        edges.put(edge.edgeId(), edge);
                    }
                }
            }
        }
        return new KHopResult(new ArrayList<>(entities.values()), new ArrayList<>(edges.values()), paths, rows.size());
    }

    String insertVertex(String tenant, GraphNode node) {
        return "USE `" + escapeIdent(space) + "`; INSERT VERTEX entity"
                + "(id, tenant, label, type, confidence, sources) VALUES \""
                + escape(node.entityId().toString()) + "\":(\""
                + escape(node.entityId().toString()) + "\", \""
                + escape(tenant) + "\", \""
                + escape(node.label()) + "\", \""
                + escape(node.type()) + "\", "
                + node.confidence() + ", \""
                + escape(SourceRefCodec.encode(node.sourceRefs())) + "\")";
    }

    String insertEdge(String tenant, GraphNode source, GraphNode target, GraphEdge edge) {
        return "USE `" + escapeIdent(space) + "`; INSERT EDGE rel"
                + "(id, tenant, verb, confidence, sources) VALUES \""
                + escape(source.entityId().toString()) + "\"->\""
                + escape(target.entityId().toString()) + "\":(\""
                + escape(edge.edgeId().toString()) + "\", \""
                + escape(tenant) + "\", \""
                + escape(edge.verb()) + "\", "
                + edge.confidence() + ", \""
                + escape(SourceRefCodec.encode(edge.sourceRefs())) + "\")";
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
        return new Edge(
                UUID.fromString(String.valueOf(row.get("edgeId") != null ? row.get("edgeId") : row.get("id"))),
                UUID.fromString(String.valueOf(row.get("fromId"))),
                UUID.fromString(String.valueOf(row.get("toId"))),
                String.valueOf(row.get("verb")),
                toDouble(row.get("confidence")),
                SourceRefCodec.decode(stringOrEmpty(row.get("sources"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
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

    static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String escapeIdent(String raw) {
        return escape(raw).replace("`", "");
    }
}
