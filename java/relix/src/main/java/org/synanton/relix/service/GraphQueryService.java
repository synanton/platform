package org.synanton.relix.service;

import org.synanton.relix.api.dto.*;
import org.synanton.relix.config.RelixProperties;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.InMemoryConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class GraphQueryService {

    private static final Logger log = LoggerFactory.getLogger(GraphQueryService.class);

    private final GraphLoader graphLoader;
    private final RelixProperties props;
    private final EntityLookupExecutor entityLookup;
    private final OneHopExecutor oneHop;
    private final KHopPathExecutor kHopPath;

    private final Map<String, AtomicReference<InMemoryConnector>> connectors = new ConcurrentHashMap<>();
    private final Map<String, Object> rebuildLocks = new ConcurrentHashMap<>();

    public GraphQueryService(GraphLoader graphLoader,
                             RelixProperties props,
                             EntityLookupExecutor entityLookup,
                             OneHopExecutor oneHop,
                             KHopPathExecutor kHopPath) {
        this.graphLoader = graphLoader;
        this.props = props;
        this.entityLookup = entityLookup;
        this.oneHop = oneHop;
        this.kHopPath = kHopPath;
    }

    public void loadTenant(String tenant) {
        GraphLoader.LoadResult result = graphLoader.load(tenant);
        InMemoryConnector connector = new InMemoryConnector(result);
        connectors.computeIfAbsent(tenant, t -> new AtomicReference<>()).set(connector);
        rebuildLocks.put(tenant, new Object());
    }

    public GraphQueryResponse query(GraphQueryRequest req) {
        String tenant = req.tenant() != null ? req.tenant() : "demo";
        InMemoryConnector connector = getConnector(tenant);
        if (connector == null) {
            throw new IllegalStateException("Graph not loaded for tenant: " + tenant);
        }

        long t0 = System.currentTimeMillis();
        long traversalStart;

        List<Entity> entities;
        List<Edge> edges;
        List<Path> paths;
        int candidateCount;

        traversalStart = System.currentTimeMillis();
        switch (req.shape()) {
            case "entity_lookup" -> {
                var result = entityLookup.execute(connector, req);
                entities = result.entities();
                edges = List.of();
                paths = List.of();
                candidateCount = result.candidateCount();
            }
            case "one_hop" -> {
                var result = oneHop.execute(connector, req);
                entities = result.entities();
                edges = result.edges();
                paths = List.of();
                candidateCount = result.candidateCount();
            }
            case "k_hop_path" -> {
                var result = kHopPath.execute(connector, req);
                entities = result.entities();
                edges = result.edges();
                paths = result.paths();
                candidateCount = result.candidateCount();
            }
            default -> throw new IllegalArgumentException("Unknown shape: " + req.shape());
        }

        long traversalMs = System.currentTimeMillis() - traversalStart;
        long totalMs = System.currentTimeMillis() - t0;
        GraphTrace trace = new GraphTrace(req.shape(), connector.generation(),
                candidateCount, traversalMs, totalMs);

        return new GraphQueryResponse(entities, edges, paths, trace);
    }

    public void rebuild(String tenant) {
        Object lock = rebuildLocks.computeIfAbsent(tenant, t -> new Object());
        synchronized (lock) {
            log.info("Rebuilding graph for tenant '{}'...", tenant);
            loadTenant(tenant);
            log.info("Graph rebuild complete for tenant '{}'", tenant);
        }
    }

    public GraphStats stats(String tenant) {
        InMemoryConnector connector = getConnector(tenant);
        if (connector == null) return new GraphStats(tenant, 0, 0, 0, -1);
        return new GraphStats(tenant, connector.entityCount(), connector.edgeCount(),
                connector.loadTimeMs(), connector.generation());
    }

    private InMemoryConnector getConnector(String tenant) {
        AtomicReference<InMemoryConnector> ref = connectors.get(tenant);
        return ref != null ? ref.get() : null;
    }
}
