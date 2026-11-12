package org.synanton.relix.adapter.out.graph.memory;

import org.synanton.common.resolution.GraphCapabilities;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.relix.api.dto.Edge;
import org.synanton.relix.api.dto.Entity;
import org.synanton.relix.api.dto.GraphStats;
import org.synanton.relix.graph.EntityNeighborhood;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.graph.GraphReadPort;
import org.synanton.relix.graph.GraphWritePort;
import org.synanton.relix.identity.UnsupportedGraphIdException;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.ExtractedMention;
import org.synanton.relix.graph.ExtractedRelation;
import org.synanton.relix.graph.InMemoryConnector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default Relix graph adapter: Cassandra Pass-2 rows hydrated into a per-tenant JGraphT graph.
 */
public class InMemoryGraphConnector implements GraphConnector, GraphReadPort, GraphWritePort {

    private final GraphLoader graphLoader;
    private final Map<String, InMemoryConnector> tenants = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ExtractedMention>> mentions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ExtractedRelation>> relations = new ConcurrentHashMap<>();

    public InMemoryGraphConnector(GraphLoader graphLoader) {
        this.graphLoader = graphLoader;
    }

    /** Test seam: install a pre-built tenant graph without Cassandra. */
    public void install(String tenant, InMemoryConnector connector) {
        tenants.put(tenant, connector);
    }

    @Override
    public java.util.Optional<Entity> getEntity(GraphIdentity identity, String entityId) {
        requireCanonical(identity);
        InMemoryConnector connector = tenants.get(identity.tenantId());
        return connector == null ? java.util.Optional.empty() : connector.entity(entityId);
    }

    @Override
    public EntityLookupResult lookupEntities(GraphIdentity identity, String label, String type, int limit) {
        requireCanonical(identity);
        return lookupEntities(identity.tenantId(), label, type, limit);
    }

    @Override
    public OneHopResult oneHop(GraphIdentity identity, UUID entityId, List<String> edgeTypes,
                               String direction, int limit) {
        requireCanonical(identity);
        return oneHop(identity.tenantId(), entityId, edgeTypes, direction, limit);
    }

    @Override
    public KHopResult kHopPaths(GraphIdentity identity, UUID fromEntityId, UUID toEntityId,
                                int maxHops, int maxPaths) {
        requireCanonical(identity);
        return kHopPaths(identity.tenantId(), fromEntityId, toEntityId, maxHops, maxPaths);
    }

    @Override
    public java.util.Optional<String> getActiveEntityType(GraphIdentity identity, String entityId) {
        requireCanonical(identity);
        return getActiveEntityType(identity.tenantId(), entityId);
    }

    @Override
    public java.util.Optional<EntityNeighborhood> neighborhood(GraphIdentity identity, String entityId, int limit) {
        requireCanonical(identity);
        return neighborhood(identity.tenantId(), entityId, limit);
    }

    @Override
    public GraphStats stats(GraphIdentity identity) {
        requireCanonical(identity);
        return stats(identity.tenantId());
    }

    @Override
    public void loadTenant(GraphIdentity identity) {
        requireCanonical(identity);
        loadTenant(identity.tenantId());
    }

    @Override
    public void upsertEntity(GraphIdentity identity, Entity entity) {
        requireCanonical(identity);
        tenants.computeIfAbsent(identity.tenantId(), ignored -> org.synanton.relix.graph.InMemoryConnector.empty())
                .upsertEntity(entity);
    }

    @Override
    public void upsertRelation(GraphIdentity identity, Edge relation) {
        requireCanonical(identity);
        tenants.computeIfAbsent(identity.tenantId(), ignored -> org.synanton.relix.graph.InMemoryConnector.empty())
                .upsertRelation(relation);
    }

    @Override
    public org.synanton.common.resolution.PromotionResult promoteRelation(
            GraphIdentity identity, org.synanton.common.resolution.CanonicalRelation relation) {
        requireCanonical(identity);
        throw new UnsupportedOperationException("in-memory graph does not support relation promotion");
    }

    private static void requireCanonical(GraphIdentity identity) {
        if (identity == null || !"canonical".equals(identity.graphId())) {
            throw new UnsupportedGraphIdException("Canonical graph port supports only graphId='canonical'");
        }
    }

    @Override
    public String id() {
        return "memory";
    }

    @Override
    public EngineDescriptor descriptor() {
        return new EngineDescriptor("memory", "in-memory-jgrapht", "ALL_NATIVE");
    }

    @Override
    public GraphCapabilities capabilities() {
        return new GraphCapabilities(false, false, true, true, false);
    }

    @Override
    public void loadTenant(String tenant) {
        tenants.put(tenant, new InMemoryConnector(graphLoader.load(tenant)));
    }

    @Override
    public GraphStats stats(String tenant) {
        InMemoryConnector connector = tenants.get(tenant);
        if (connector == null) {
            return new GraphStats(tenant, 0, 0, 0, -1);
        }
        return new GraphStats(tenant, connector.entityCount(), connector.edgeCount(),
                connector.loadTimeMs(), connector.generation());
    }

    @Override
    public EntityLookupResult lookupEntities(String tenant, String label, String type, int limit) {
        InMemoryConnector connector = requireTenant(tenant);
        return connector.lookupEntities(label, type, limit);
    }

    @Override
    public OneHopResult oneHop(String tenant, UUID entityId, List<String> edgeTypes, String direction, int limit) {
        InMemoryConnector connector = requireTenant(tenant);
        return connector.oneHop(entityId, edgeTypes, direction, limit);
    }

    @Override
    public KHopResult kHopPaths(String tenant, UUID fromEntityId, UUID toEntityId, int maxHops, int maxPaths) {
        InMemoryConnector connector = requireTenant(tenant);
        return connector.kHopPaths(fromEntityId, toEntityId, maxHops, maxPaths);
    }

    @Override
    public java.util.Optional<String> getActiveEntityType(String tenant, String entityId) {
        InMemoryConnector connector = tenants.get(tenant);
        return connector == null ? java.util.Optional.empty() : connector.activeType(entityId);
    }

    @Override
    public java.util.List<org.synanton.common.resolution.ExtractedRelationView> listExtractedRelations(
            String tenant, String sourceVersionId) {
        Map<String, ExtractedRelation> tenantRelations = relations.getOrDefault(tenant, Map.of());
        return tenantRelations.values().stream()
                .filter(relation -> sourceVersionId == null || sourceVersionId.equals(relation.sourceVersionId()))
                .map(relation -> new org.synanton.common.resolution.ExtractedRelationView(
                        relation.relationId(), tenant, relation.sourceVersionId(),
                        relation.subjectMentionId(), relation.objectMentionId(), relation.predicate(),
                        relation.evidenceChunkId(), relation.evidenceChunkOffsetStart(),
                        relation.evidenceChunkOffsetEnd()))
                .toList();
    }

    @Override
    public int upsertMentions(List<ExtractedMention> batch) {
        for (ExtractedMention mention : batch) {
            mentions.computeIfAbsent(mention.tenantId(), ignored -> new ConcurrentHashMap<>())
                    .put(mention.mentionId(), mention);
        }
        return (int) batch.stream().map(ExtractedMention::mentionId).distinct().count();
    }

    @Override
    public int upsertRelations(List<ExtractedRelation> batch) {
        for (ExtractedRelation relation : batch) {
            relations.computeIfAbsent(relation.tenantId(), ignored -> new ConcurrentHashMap<>())
                    .put(relation.relationId(), relation);
        }
        return (int) batch.stream().map(ExtractedRelation::relationId).distinct().count();
    }

    public int extractedMentionCount(String tenant) {
        return mentions.getOrDefault(tenant, Map.of()).size();
    }

    public int extractedRelationCount(String tenant) {
        return relations.getOrDefault(tenant, Map.of()).size();
    }

    private InMemoryConnector requireTenant(String tenant) {
        InMemoryConnector connector = tenants.get(tenant);
        if (connector == null) {
            throw new IllegalStateException("Graph not loaded for tenant: " + tenant);
        }
        return connector;
    }
}
