package org.synanton.relix.service;

import org.synanton.relix.api.dto.Edge;
import org.synanton.relix.api.dto.Entity;
import org.synanton.relix.api.dto.GraphQueryRequest;
import org.synanton.relix.api.dto.GraphQueryResponse;
import org.synanton.relix.api.dto.GraphStats;
import org.synanton.relix.api.dto.GraphTrace;
import org.synanton.relix.api.dto.Path;
import org.synanton.relix.graph.GraphConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GraphQueryService {

    private static final Logger log = LoggerFactory.getLogger(GraphQueryService.class);

    private final GraphConnector graphConnector;
    private final EntityLookupExecutor entityLookup;
    private final OneHopExecutor oneHop;
    private final KHopPathExecutor kHopPath;
    private final Map<String, Object> rebuildLocks = new ConcurrentHashMap<>();

    public GraphQueryService(GraphConnector graphConnector,
                             EntityLookupExecutor entityLookup,
                             OneHopExecutor oneHop,
                             KHopPathExecutor kHopPath) {
        this.graphConnector = graphConnector;
        this.entityLookup = entityLookup;
        this.oneHop = oneHop;
        this.kHopPath = kHopPath;
    }

    public void loadTenant(String tenant) {
        graphConnector.loadTenant(tenant);
    }

    public GraphQueryResponse query(GraphQueryRequest req) {
        long t0 = System.currentTimeMillis();
        long traversalStart = System.currentTimeMillis();

        List<Entity> entities;
        List<Edge> edges;
        List<Path> paths;
        int candidateCount;

        switch (req.shape()) {
            case "entity_lookup" -> {
                var result = entityLookup.execute(graphConnector, req);
                entities = result.entities();
                edges = List.of();
                paths = List.of();
                candidateCount = result.candidateCount();
            }
            case "one_hop" -> {
                var result = oneHop.execute(graphConnector, req);
                entities = result.entities();
                edges = result.edges();
                paths = List.of();
                candidateCount = result.candidateCount();
            }
            case "k_hop_path" -> {
                var result = kHopPath.execute(graphConnector, req);
                entities = result.entities();
                edges = result.edges();
                paths = result.paths();
                candidateCount = result.candidateCount();
            }
            default -> throw new IllegalArgumentException("Unknown shape: " + req.shape());
        }

        long traversalMs = System.currentTimeMillis() - traversalStart;
        long totalMs = System.currentTimeMillis() - t0;
        long generation = graphConnector.stats(req.tenant() != null ? req.tenant() : "demo").graphGeneration();
        GraphTrace trace = new GraphTrace(req.shape(), generation, candidateCount, traversalMs, totalMs);
        return new GraphQueryResponse(entities, edges, paths, trace);
    }

    public void rebuild(String tenant) {
        Object lock = rebuildLocks.computeIfAbsent(tenant, ignored -> new Object());
        synchronized (lock) {
            log.info("Rebuilding graph for tenant '{}' via connector '{}'", tenant, graphConnector.id());
            loadTenant(tenant);
            log.info("Graph rebuild complete for tenant '{}'", tenant);
        }
    }

    public GraphStats stats(String tenant) {
        return graphConnector.stats(tenant);
    }
}
