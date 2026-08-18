package org.synanton.relix.service;

import org.jgrapht.graph.DirectedMultigraph;
import org.synanton.relix.api.dto.*;
import org.synanton.relix.graph.GraphEdge;
import org.synanton.relix.graph.GraphNode;
import org.synanton.relix.graph.InMemoryConnector;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class KHopPathExecutor {

    private static final int MAX_HOPS_CAP = 6;
    private static final int MAX_PATHS_CAP = 100;

    public record Result(List<Entity> entities, List<Edge> edges, List<Path> paths, int candidateCount) {}

    public Result execute(InMemoryConnector connector, GraphQueryRequest req) {
        UUID fromId = req.paramUuid("from_entity_id");
        UUID toId = req.paramUuid("to_entity_id");
        int maxHops = Math.min(req.paramInt("max_hops", 4), MAX_HOPS_CAP);
        int maxPaths = Math.min(req.paramInt("max_paths", 10), MAX_PATHS_CAP);

        GraphNode fromNode = connector.nodeById().get(fromId);
        GraphNode toNode = connector.nodeById().get(toId);
        if (fromNode == null || toNode == null) {
            return new Result(List.of(), List.of(), List.of(), 0);
        }

        DirectedMultigraph<GraphNode, GraphEdge> graph = connector.graph();
        List<List<Object>> rawPaths = new ArrayList<>();
        bfs(graph, fromNode, toNode, maxHops, maxPaths, rawPaths);

        int candidateCount = rawPaths.size();
        Set<GraphNode> nodeSet = new LinkedHashSet<>();
        Set<GraphEdge> edgeSet = new LinkedHashSet<>();
        List<Path> paths = new ArrayList<>();

        for (List<Object> raw : rawPaths) {
            List<String> hops = new ArrayList<>();
            double score = 1.0;
            for (Object o : raw) {
                if (o instanceof GraphNode n) {
                    nodeSet.add(n);
                    hops.add(n.entityId().toString());
                } else if (o instanceof GraphEdge e) {
                    edgeSet.add(e);
                    hops.add(e.edgeId().toString());
                    score *= e.confidence();
                }
            }
            paths.add(new Path(hops, score));
        }

        // Sort by descending score
        paths.sort(Comparator.comparingDouble(Path::score).reversed());

        List<Entity> entities = nodeSet.stream().map(DtoMapper::toDto).toList();
        List<Edge> edges = edgeSet.stream().map(e -> {
            GraphNode src = graph.getEdgeSource(e);
            GraphNode tgt = graph.getEdgeTarget(e);
            return DtoMapper.toDto(e, src.entityId(), tgt.entityId());
        }).toList();

        return new Result(entities, edges, paths.stream().limit(maxPaths).toList(), candidateCount);
    }

    /** BFS collecting all paths from start to end within maxHops. */
    private void bfs(DirectedMultigraph<GraphNode, GraphEdge> graph,
                     GraphNode start, GraphNode end,
                     int maxHops, int maxPaths,
                     List<List<Object>> results) {
        // State: (currentNode, pathSoFar, visitedNodes)
        Deque<State> queue = new ArrayDeque<>();
        List<Object> initial = new ArrayList<>();
        initial.add(start);
        queue.add(new State(start, initial, new HashSet<>(Set.of(start))));

        while (!queue.isEmpty() && results.size() < maxPaths) {
            State state = queue.poll();
            if (state.path.size() > maxHops * 2 + 1) continue;

            for (GraphEdge edge : graph.outgoingEdgesOf(state.current)) {
                GraphNode next = graph.getEdgeTarget(edge);
                if (state.visited.contains(next)) continue;

                List<Object> newPath = new ArrayList<>(state.path);
                newPath.add(edge);
                newPath.add(next);

                if (next.equals(end)) {
                    results.add(newPath);
                    if (results.size() >= maxPaths) return;
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
