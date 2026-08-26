package org.synanton.relix.graph;

import org.synanton.relix.api.dto.Edge;
import org.synanton.relix.api.dto.Entity;
import org.synanton.relix.api.dto.GraphStats;
import org.synanton.relix.api.dto.Path;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port for the graph engine. Domain query shapes never import JGraphT, Cypher, or nGQL.
 * Switch implementations with {@code relix.graph.connector} ({@code memory}, {@code neo4j}, {@code nebula}).
 */
public interface GraphConnector {

    String id();

    EngineDescriptor descriptor();

    void loadTenant(String tenant);

    GraphStats stats(String tenant);

    EntityLookupResult lookupEntities(String tenant, String label, String type, int limit);

    OneHopResult oneHop(String tenant, UUID entityId, List<String> edgeTypes, String direction, int limit);

    KHopResult kHopPaths(String tenant, UUID fromEntityId, UUID toEntityId, int maxHops, int maxPaths);

    record EngineDescriptor(String connectorId, String engine, String patternCoverage) {}

    record EntityLookupResult(List<Entity> entities, int candidateCount) {}

    record OneHopResult(List<Entity> entities, List<Edge> edges, int candidateCount) {}

    record KHopResult(List<Entity> entities, List<Edge> edges, List<Path> paths, int candidateCount) {}
}
