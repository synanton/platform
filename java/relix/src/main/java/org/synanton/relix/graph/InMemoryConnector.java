package org.synanton.relix.graph;

import org.jgrapht.graph.DirectedMultigraph;
import org.synanton.relix.api.dto.Edge;
import org.synanton.relix.api.dto.Entity;
import org.synanton.relix.api.dto.Path;
import org.synanton.relix.index.EntityIndex;
import org.synanton.relix.service.DtoMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Heap-resident tenant graph (JGraphT). Not a {@link GraphConnector}; wrapped by {@link InMemoryGraphConnector}.
 */
public class InMemoryConnector {

    private static final int MAX_HOPS_CAP = 6;
    private static final int MAX_PATHS_CAP = 100;

    private final DirectedMultigraph<GraphNode, GraphEdge> graph;
    private final EntityIndex entityIndex;
    private final Map<UUID, GraphNode> nodeById;
    private final long generation;
    private final long loadTimeMs;

    public InMemoryConnector(GraphLoader.LoadResult result) {
        this.graph = result.graph();
        this.entityIndex = result.entityIndex();
        this.nodeById = result.nodeById();
        this.generation = result.generation();
        this.loadTimeMs = result.loadTimeMs();
    }

    public long generation() {
        return generation;
    }

    public long loadTimeMs() {
        return loadTimeMs;
    }

    public int entityCount() {
        return graph.vertexSet().size();
    }

    public int edgeCount() {
        return graph.edgeSet().size();
    }

    public GraphConnector.EngineDescriptor getEngineDescriptor() {
        return new GraphConnector.EngineDescriptor("memory", "in-memory-jgrapht", "ALL_NATIVE");
    }

    public void executeBulkMutation(Object mutation) {
        throw new UnsupportedOperationException("InMemoryConnector is read-only");
    }

    public GraphConnector.EntityLookupResult lookupEntities(String label, String type, int limit) {
        if (label == null || label.isBlank()) {
            return new GraphConnector.EntityLookupResult(List.of(), 0);
        }
        var candidates = entityIndex.lookup(label, type);
        List<Entity> entities = candidates.stream()
                .limit(limit)
                .map(DtoMapper::toDto)
                .collect(Collectors.toList());
        return new GraphConnector.EntityLookupResult(entities, candidates.size());
    }

    public GraphConnector.OneHopResult oneHop(UUID entityId, List<String> edgeTypes, String direction, int limit) {
        String resolvedDirection = direction == null ? "OUT" : direction;
        GraphNode center = nodeById.get(entityId);
        if (center == null) {
            return new GraphConnector.OneHopResult(List.of(), List.of(), 0);
        }

        Set<GraphEdge> rawEdges = new LinkedHashSet<>();
        if ("OUT".equalsIgnoreCase(resolvedDirection) || "BOTH".equalsIgnoreCase(resolvedDirection)) {
            rawEdges.addAll(graph.outgoingEdgesOf(center));
        }
        if ("IN".equalsIgnoreCase(resolvedDirection) || "BOTH".equalsIgnoreCase(resolvedDirection)) {
            rawEdges.addAll(graph.incomingEdgesOf(center));
        }
        if (edgeTypes != null && !edgeTypes.isEmpty()) {
            rawEdges.removeIf(edge -> !edgeTypes.contains(edge.verb()));
        }

        int candidateCount = rawEdges.size();
        List<Edge> edges = new ArrayList<>();
        Set<GraphNode> neighbourNodes = new LinkedHashSet<>();
        rawEdges.stream().limit(limit).forEach(edge -> {
            GraphNode source = graph.getEdgeSource(edge);
            GraphNode target = graph.getEdgeTarget(edge);
            edges.add(DtoMapper.toDto(edge, source.entityId(), target.entityId()));
            neighbourNodes.add(source == center ? target : source);
        });
        List<Entity> entities = neighbourNodes.stream().map(DtoMapper::toDto).collect(Collectors.toList());
        return new GraphConnector.OneHopResult(entities, edges, candidateCount);
    }

    public GraphConnector.KHopResult kHopPaths(UUID fromId, UUID toId, int maxHops, int maxPaths) {
        int hops = Math.min(maxHops, MAX_HOPS_CAP);
        int pathLimit = Math.min(maxPaths, MAX_PATHS_CAP);
        GraphNode fromNode = nodeById.get(fromId);
        GraphNode toNode = nodeById.get(toId);
        if (fromNode == null || toNode == null) {
            return new GraphConnector.KHopResult(List.of(), List.of(), List.of(), 0);
        }

        List<List<Object>> rawPaths = new ArrayList<>();
        bfs(fromNode, toNode, hops, pathLimit, rawPaths);

        Set<GraphNode> nodeSet = new LinkedHashSet<>();
        Set<GraphEdge> edgeSet = new LinkedHashSet<>();
        List<Path> paths = new ArrayList<>();
        for (List<Object> raw : rawPaths) {
            List<String> hopIds = new ArrayList<>();
            double score = 1.0;
            for (Object element : raw) {
                if (element instanceof GraphNode node) {
                    nodeSet.add(node);
                    hopIds.add(node.entityId().toString());
                } else if (element instanceof GraphEdge edge) {
                    edgeSet.add(edge);
                    hopIds.add(edge.edgeId().toString());
                    score *= edge.confidence();
                }
            }
            paths.add(new Path(hopIds, score));
        }
        paths.sort(Comparator.comparingDouble(Path::score).reversed());

        List<Entity> entities = nodeSet.stream().map(DtoMapper::toDto).toList();
        List<Edge> edges = edgeSet.stream().map(edge -> {
            GraphNode source = graph.getEdgeSource(edge);
            GraphNode target = graph.getEdgeTarget(edge);
            return DtoMapper.toDto(edge, source.entityId(), target.entityId());
        }).toList();
        return new GraphConnector.KHopResult(entities, edges, paths.stream().limit(pathLimit).toList(), rawPaths.size());
    }

    private void bfs(GraphNode start, GraphNode end, int maxHops, int maxPaths, List<List<Object>> results) {
        Deque<State> queue = new ArrayDeque<>();
        List<Object> initial = new ArrayList<>();
        initial.add(start);
        queue.add(new State(start, initial, new HashSet<>(Set.of(start))));

        while (!queue.isEmpty() && results.size() < maxPaths) {
            State state = queue.poll();
            if (state.path.size() > maxHops * 2 + 1) {
                continue;
            }
            for (GraphEdge edge : graph.outgoingEdgesOf(state.current)) {
                GraphNode next = graph.getEdgeTarget(edge);
                if (state.visited.contains(next)) {
                    continue;
                }
                List<Object> newPath = new ArrayList<>(state.path);
                newPath.add(edge);
                newPath.add(next);
                if (next.equals(end)) {
                    results.add(newPath);
                    if (results.size() >= maxPaths) {
                        return;
                    }
                } else if (newPath.size() < maxHops * 2 + 1) {
                    Set<GraphNode> newVisited = new HashSet<>(state.visited);
                    newVisited.add(next);
                    queue.add(new State(next, newPath, newVisited));
                }
            }
        }
    }

    private record State(GraphNode current, List<Object> path, Set<GraphNode> visited) {}
}
