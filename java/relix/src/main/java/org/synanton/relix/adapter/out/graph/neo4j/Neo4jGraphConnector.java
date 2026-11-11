package org.synanton.relix.adapter.out.graph.neo4j;

import org.jgrapht.graph.DirectedMultigraph;
import org.synanton.relix.api.dto.Edge;
import org.synanton.relix.api.dto.Entity;
import org.synanton.relix.api.dto.GraphStats;
import org.synanton.relix.api.dto.Path;
import org.synanton.relix.api.dto.SourceRef;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.graph.GraphEdge;
import org.synanton.relix.graph.ExtractedMention;
import org.synanton.relix.graph.ExtractedRelation;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.GraphNode;
import org.synanton.relix.graph.EntityNeighborhood;
import org.synanton.relix.graph.SourceRefCodec;
import org.synanton.relix.graph.ResolutionIds;
import org.synanton.relix.graph.PromotionIds;
import org.synanton.common.resolution.ActiveResolution;
import org.synanton.common.resolution.ExtractedRelationView;
import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.PromotionResult;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.common.resolution.StaleResolutionException;
import org.synanton.common.resolution.ContractViolationException;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;
import org.synanton.common.resolution.GraphResolutionObservation;
import org.synanton.common.resolution.ResolutionKey;
import org.synanton.common.resolution.ResolutionWriteOutcome;

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
    private volatile boolean extractedSchemaEnsured;

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

        // Loading a tenant is additive: Cassandra is a projection/staging input,
        // not authority to delete canonical graph state. Replacing this with a
        // destructive reload would erase existing :Entity/:REL on every restart.
        // Explicit deletions belong to tombstone/supersession workflows.
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
        if (!nodes.isEmpty() || !edges.isEmpty()) {
            // One transaction: nodes and edges commit or roll back together.
            // Existing canonical entities are never deleted by a projection
            // load; this query is additive/upsert-only.
            cypher.write("""
                    UNWIND $nodes AS node
                    MERGE (n:Entity {id: node.id, tenant: $tenant})
                    SET n.label = node.label, n.type = node.type,
                        n.confidence = node.confidence, n.sources = node.sources
                    WITH collect(n) AS loadedNodes
                    UNWIND $edges AS edge
                    MATCH (a:Entity {id: edge.fromId, tenant: $tenant})
                    MATCH (b:Entity {id: edge.toId, tenant: $tenant})
                    MERGE (a)-[r:REL {id: edge.id}]->(b)
                    SET r.verb = edge.verb, r.confidence = edge.confidence,
                        r.sources = edge.sources
                    """, Map.of("tenant", tenant, "nodes", nodes, "edges", edges));
        }

        long loadTimeMs = System.currentTimeMillis() - start;
        statsByTenant.put(tenant, new GraphStats(tenant, nodes.size(), edges.size(), loadTimeMs, loaded.generation()));
    }

    @Override
    public boolean supportsResolutionReconciliation() {
        return true;
    }

    @Override
    public List<ActiveResolution> getActiveResolutions(String tenant, List<String> mentionIds) {
        if (mentionIds == null || mentionIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = mentionIds.stream().filter(id -> id != null && !id.isBlank())
                .distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = cypher.read("""
                UNWIND $ids AS id
                MATCH (m:ExtractedMention {tenant_id: $tenant, mention_id: id})
                      -[r:RESOLVES_TO {active: true}]->
                      (e:Entity {tenant: $tenant})
                RETURN id AS mention_id, e.id AS canonical_entity_id, e.type AS canonical_entity_type
                """, Map.of("tenant", tenant, "ids", ids));
        List<ActiveResolution> resolutions = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            resolutions.add(new ActiveResolution(
                    String.valueOf(row.get("mention_id")),
                    String.valueOf(row.get("canonical_entity_id")),
                    row.get("canonical_entity_type") == null ? null
                            : String.valueOf(row.get("canonical_entity_type"))));
        }
        return resolutions;
    }

    /**
     * Mention-scoped read: source version is observed and compared by policy, not
     * part of the query key. LIMIT 2 + multipleActive surfaces pre-existing
     * duplicate active edges instead of silently selecting one.
     */
    @Override
    public java.util.Optional<GraphResolutionObservation> activeResolution(ResolutionKey key) {
        if (key == null || key.tenantId() == null || key.mentionId() == null
                || key.mentionId().isBlank()) {
            return java.util.Optional.empty();
        }
        List<Map<String, Object>> rows = cypher.read("""
                MATCH (m:ExtractedMention {tenant_id: $tenant, mention_id: $mentionId})
                      -[r:RESOLVES_TO {active: true}]->
                      (e:Entity {tenant: $tenant})
                RETURN r.source_version_id AS source_version_id,
                       r.resolution_id AS resolution_id, e.id AS entity_id,
                       coalesce(r.fencing_token, 0) AS fencing_token,
                       r.policy_version AS policy_version,
                       r.ontology_version AS ontology_version,
                       coalesce(r.best_score, 0.0) AS best_score,
                       coalesce(r.runner_up_score, 0.0) AS runner_up_score,
                       r.retrieval_strategy AS retrieval_strategy,
                       r.search_generation AS search_generation
                LIMIT 2
                """, Map.of("tenant", key.tenantId(), "mentionId", key.mentionId()));
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        return java.util.Optional.of(new GraphResolutionObservation(
                row.get("source_version_id") == null ? null
                        : String.valueOf(row.get("source_version_id")),
                row.get("resolution_id") == null ? null
                        : String.valueOf(row.get("resolution_id")),
                row.get("entity_id") == null ? null : String.valueOf(row.get("entity_id")),
                toLong(row.get("fencing_token")),
                row.get("policy_version") == null ? null
                        : String.valueOf(row.get("policy_version")),
                row.get("ontology_version") == null ? null
                        : String.valueOf(row.get("ontology_version")),
                toDouble(row.get("best_score")),
                toDouble(row.get("runner_up_score")),
                row.get("retrieval_strategy") == null ? null
                        : String.valueOf(row.get("retrieval_strategy")),
                rows.size() > 1,
                row.get("search_generation") == null ? null
                        : String.valueOf(row.get("search_generation"))));
    }

    @Override
    public boolean hasResolutionHistory(ResolutionKey key) {
        if (key == null || key.tenantId() == null || key.mentionId() == null
                || key.mentionId().isBlank()) {
            return false;
        }
        List<Map<String, Object>> rows = cypher.read("""
                MATCH (m:ExtractedMention {tenant_id: $tenant, mention_id: $mentionId})
                      -[r:RESOLVES_TO]->
                      (:Entity {tenant: $tenant})
                RETURN count(r) AS history_count
                """, Map.of("tenant", key.tenantId(), "mentionId", key.mentionId()));
        return !rows.isEmpty() && toLong(rows.getFirst().get("history_count")) > 0;
    }

    @Override
    public ResolutionWriteOutcome ensureResolution(ResolutionDecision decision) {
        try {
            upsertResolution(decision);
            return ResolutionWriteOutcome.APPLIED;
        } catch (StaleResolutionException | ContractViolationException | IllegalArgumentException e) {
            // Proven permanent rejection: stale fence or invalid intent.
            return ResolutionWriteOutcome.REJECTED;
        } catch (RetryableProcessingException e) {
            return ResolutionWriteOutcome.RETRYABLE;
        } catch (RuntimeException e) {
            // Unknown driver/session failure: unknown is retryable, never a contradiction.
            throw new RetryableProcessingException("Resolution graph intent failed unexpectedly", e);
        }
    }

    @Override
    public java.util.Optional<ExtractedRelationView> getExtractedRelation(String tenant, String relationId) {
        if (relationId == null || relationId.isBlank()) {
            return java.util.Optional.empty();
        }
        List<Map<String, Object>> rows = cypher.read("""
                MATCH (s:ExtractedMention {tenant_id: $tenant})
                      -[r:EXTRACTED_RELATION {tenant_id: $tenant, relation_id: $relationId}]->
                      (o:ExtractedMention {tenant_id: $tenant})
                RETURN r.relation_id AS relation_id, s.mention_id AS subject_mention_id,
                       o.mention_id AS object_mention_id, r.predicate AS predicate,
                       r.source_version_id AS source_version_id, r.evidence_chunk_id AS evidence_chunk_id,
                       r.evidence_chunk_offset_start AS offset_start,
                       r.evidence_chunk_offset_end AS offset_end
                LIMIT 1
                """, Map.of("tenant", tenant, "relationId", relationId));
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        return java.util.Optional.of(new ExtractedRelationView(
                String.valueOf(row.get("relation_id")),
                tenant,
                String.valueOf(row.get("source_version_id")),
                String.valueOf(row.get("subject_mention_id")),
                String.valueOf(row.get("object_mention_id")),
                String.valueOf(row.get("predicate")),
                String.valueOf(row.get("evidence_chunk_id")),
                row.get("offset_start") == null ? -1 : ((Number) row.get("offset_start")).intValue(),
                row.get("offset_end") == null ? -1 : ((Number) row.get("offset_end")).intValue()));
    }

    private static Map<String, Object> params(String tenant, String sourceVersionId) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("tenant", tenant);
        params.put("version", sourceVersionId);
        return params;
    }

    @Override
    public java.util.List<ExtractedRelationView> listExtractedRelations(String tenant, String sourceVersionId) {
        List<Map<String, Object>> rows = cypher.read("""
                MATCH (s:ExtractedMention {tenant_id: $tenant})
                      -[r:EXTRACTED_RELATION {tenant_id: $tenant}]->
                      (o:ExtractedMention {tenant_id: $tenant})
                WHERE $version IS NULL OR r.source_version_id = $version
                RETURN r.relation_id AS relation_id, r.source_version_id AS source_version_id,
                       s.mention_id AS subject_mention_id, o.mention_id AS object_mention_id,
                       r.predicate AS predicate, r.evidence_chunk_id AS evidence_chunk_id,
                       r.evidence_chunk_offset_start AS offset_start,
                       r.evidence_chunk_offset_end AS offset_end
                ORDER BY r.relation_id
                LIMIT 1000
                """, params(tenant, sourceVersionId));
        return rows.stream().map(row -> new ExtractedRelationView(
                String.valueOf(row.get("relation_id")), tenant,
                String.valueOf(row.get("source_version_id")),
                String.valueOf(row.get("subject_mention_id")),
                String.valueOf(row.get("object_mention_id")),
                String.valueOf(row.get("predicate")),
                String.valueOf(row.get("evidence_chunk_id")),
                row.get("offset_start") == null ? -1 : ((Number) row.get("offset_start")).intValue(),
                row.get("offset_end") == null ? -1 : ((Number) row.get("offset_end")).intValue()
        )).toList();
    }

    @Override
    public PromotionResult promoteRelation(CanonicalRelation relation) {
        ensureExtractedSchema();
        String canonicalRelationId = PromotionIds.canonicalRelationId(relation);
        String evidenceId = PromotionIds.evidenceId(relation);
        String predicate = PromotionIds.normalizePredicate(relation.predicate());
        String now = java.time.Instant.now().toString();
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("tenant", relation.tenantId());
        params.put("sourceVersionId", relation.sourceVersionId());
        params.put("extractedRelationId", relation.extractedRelationId());
        params.put("subjectMentionId", relation.subjectMentionId());
        params.put("subjectEntityId", relation.subjectEntityId());
        params.put("objectMentionId", relation.objectMentionId());
        params.put("objectEntityId", relation.objectEntityId());
        params.put("predicate", predicate);
        params.put("ontologyVersion", relation.ontologyVersion());
        params.put("evidenceChunkId", relation.evidenceChunkId());
        params.put("offsetStart", relation.evidenceChunkOffsetStart());
        params.put("offsetEnd", relation.evidenceChunkOffsetEnd());
        params.put("canonicalRelationId", canonicalRelationId);
        params.put("evidenceId", evidenceId);
        params.put("now", now);

        List<Map<String, Object>> rows;
        try {
            rows = cypher.writeReturning("""
                WITH [$subjectMentionId, $objectMentionId] AS mentionIds
                UNWIND mentionIds AS mentionId
                WITH DISTINCT mentionId
                ORDER BY mentionId
                MERGE (slot:ResolutionSlot {tenant_id: $tenant, mention_id: mentionId})
                SET slot.locked_at = $now
                WITH collect(slot) AS slots
                MATCH (s:ExtractedMention {tenant_id: $tenant, mention_id: $subjectMentionId})
                      -[:RESOLVES_TO {active: true}]->
                      (se:Entity {tenant: $tenant, id: $subjectEntityId})
                MATCH (o:ExtractedMention {tenant_id: $tenant, mention_id: $objectMentionId})
                      -[:RESOLVES_TO {active: true}]->
                      (oe:Entity {tenant: $tenant, id: $objectEntityId})
                MERGE (anchor:CanonicalRelationAnchor {tenant_id: $tenant, canonical_relation_id: $canonicalRelationId})
                SET anchor.updated_at = $now, anchor.predicate = $predicate,
                    anchor.subject_entity_id = $subjectEntityId,
                    anchor.object_entity_id = $objectEntityId
                WITH se, oe
                OPTIONAL MATCH (se)-[existingRel:REL {canonical_relation_id: $canonicalRelationId}]->(oe)
                OPTIONAL MATCH (existingEv:RelationEvidence {tenant_id: $tenant, evidence_id: $evidenceId})
                WITH se, oe, existingRel, existingEv
                MERGE (se)-[rel:REL {canonical_relation_id: $canonicalRelationId}]->(oe)
                  ON CREATE SET rel.created_at = $now
                SET rel.predicate = $predicate, rel.tenant_id = $tenant,
                    rel.ontology_version = $ontologyVersion, rel.updated_at = $now
                MERGE (ev:RelationEvidence {tenant_id: $tenant, evidence_id: $evidenceId})
                  ON CREATE SET ev.created_at = $now
                SET ev.canonical_relation_id = $canonicalRelationId,
                    ev.source_version_id = $sourceVersionId,
                    ev.extracted_relation_id = $extractedRelationId,
                    ev.evidence_chunk_id = $evidenceChunkId,
                    ev.evidence_offset_start = $offsetStart,
                    ev.evidence_offset_end = $offsetEnd
                MERGE (xr:ExtractedRelation {tenant_id: $tenant, relation_id: $extractedRelationId})
                SET xr.source_version_id = $sourceVersionId,
                    xr.subject_mention_id = $subjectMentionId,
                    xr.object_mention_id = $objectMentionId,
                    xr.predicate = $predicate
                MERGE (xr)-[sup:SUPPORTS {tenant_id: $tenant}]->(ev)
                RETURN CASE WHEN existingRel IS NULL THEN 1 ELSE 0 END AS relations_created,
                       CASE WHEN existingEv IS NULL THEN 1 ELSE 0 END AS evidence_created,
                       1 AS evidence_matched
                """, params);
        } catch (RuntimeException e) {
            throw new RetryableProcessingException("Canonical relation promotion transaction failed", e);
        }
        if (rows.isEmpty()) {
            return new PromotionResult(0, 0, 0);
        }
        Map<String, Object> row = rows.getFirst();
        return new PromotionResult(
                ((Number) row.get("relations_created")).intValue(),
                ((Number) row.get("evidence_created")).intValue(),
                ((Number) row.get("evidence_matched")).intValue());
    }

    @Override
    public void upsertResolution(ResolutionDecision decision) {
        if (decision == null || decision.outcome() != ResolutionOutcome.RESOLVED
                || decision.resolvedEntityId() == null || decision.resolvedEntityId().isBlank()) {
            return; // only RESOLVED creates an active :RESOLVES_TO link
        }
        ensureExtractedSchema();
        String tenant = decision.tenantId();
        String version = decision.sourceVersionId();
        String mentionId = decision.mentionId();
        String entityId = decision.resolvedEntityId();
        if (tenant == null || version == null || mentionId == null) {
            throw new IllegalArgumentException("resolution tenant/version/mention are required");
        }
        String resolutionId = ResolutionIds.of(decision);
        String now = java.time.Instant.now().toString();
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("tenant", tenant);
        params.put("mentionId", mentionId);
        params.put("entityId", entityId);
        params.put("resolutionId", resolutionId);
        params.put("sourceVersionId", version);
        params.put("policyVersion", decision.strategyVersion());
        params.put("retrievalStrategy", decision.retrievalStrategy());
        params.put("bestScore", decision.bestScore());
        params.put("runnerUpScore", decision.runnerUpScore());
        params.put("ontologyVersion", decision.ontologyVersion());
        params.put("fencingToken", decision.fencingToken());
        params.put("searchGeneration", decision.searchGeneration());
        params.put("now", now);

        List<Map<String, Object>> appliedRows = cypher.writeReturning("""
                MERGE (slot:ResolutionSlot {tenant_id: $tenant, mention_id: $mentionId})
                SET slot.locked_at = $now
                WITH slot
                MATCH (m:ExtractedMention {tenant_id: $tenant, mention_id: $mentionId})
                MATCH (e:Entity {tenant: $tenant, id: $entityId})
                OPTIONAL MATCH (m)-[old:RESOLVES_TO {active: true}]->(:Entity)
                WITH m, e, collect(old) AS olds
                WITH m, e, olds,
                     reduce(maxToken = -1, o IN olds |
                         CASE WHEN coalesce(o.fencing_token, 0) > maxToken
                              THEN coalesce(o.fencing_token, 0) ELSE maxToken END) AS currentToken
                WHERE currentToken <= $fencingToken
                FOREACH (o IN [x IN olds WHERE x.resolution_id <> $resolutionId] |
                    SET o.active = false, o.status = 'SUPERSEDED', o.superseded_at = $now)
                MERGE (m)-[r:RESOLVES_TO {resolution_id: $resolutionId}]->(e)
                SET r.active = true, r.status = 'ACTIVE', r.tenant_id = $tenant,
                    r.source_version_id = $sourceVersionId, r.mention_id = $mentionId,
                    r.canonical_entity_id = $entityId, r.policy_version = $policyVersion,
                    r.retrieval_strategy = $retrievalStrategy, r.best_score = $bestScore,
                    r.runner_up_score = $runnerUpScore, r.ontology_version = $ontologyVersion,
                    r.search_generation = $searchGeneration,
                    r.fencing_token = $fencingToken, r.decision_at = $now, r.superseded_at = null
                RETURN count(r) AS applied
                """, params);
        Object appliedCount = appliedRows.isEmpty() ? 0 : appliedRows.getFirst().get("applied");
        if (!(appliedCount instanceof Number number) || number.longValue() == 0) {
            throw new StaleResolutionException("Stale fencing token or missing resolution targets for mention "
                    + mentionId);
        }
    }

    @Override
    public void supersedeResolution(String tenantId, String sourceVersionId,
                                    String mentionId, java.time.Instant supersededAt) {
        cypher.write("""
                MERGE (slot:ResolutionSlot {tenant_id: $tenant, mention_id: $mentionId})
                SET slot.locked_at = $at
                WITH slot
                MATCH (m:ExtractedMention {tenant_id: $tenant, mention_id: $mentionId})
                -[r:RESOLVES_TO {active: true}]->(:Entity)
                SET r.active = false, r.status = 'SUPERSEDED', r.superseded_at = $at
                """, Map.of("tenant", tenantId, "mentionId", mentionId,
                "at", (supersededAt == null ? java.time.Instant.now() : supersededAt).toString()));
    }

    @Override
    public java.util.Optional<String> getActiveEntityType(String tenant, String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return java.util.Optional.empty();
        }
        List<Map<String, Object>> rows = cypher.read(
                "MATCH (n:Entity {tenant: $tenant, id: $id}) RETURN n.type AS type",
                Map.of("tenant", tenant, "id", entityId));
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        Object type = rows.getFirst().get("type");
        return type == null ? java.util.Optional.empty() : java.util.Optional.of(String.valueOf(type));
    }

    @Override
    public int upsertMentions(List<ExtractedMention> batch) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        ensureExtractedSchema();
        String tenant = batch.getFirst().tenantId();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExtractedMention mention : batch) {
            if (!tenant.equals(mention.tenantId())) {
                throw new IllegalArgumentException("all extracted mentions in a write must share a tenant");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mentionId", mention.mentionId());
            row.put("sourceVersionId", mention.sourceVersionId());
            row.put("contentRefId", mention.contentRefId());
            row.put("chunkId", mention.chunkId());
            row.put("chunkOffsetStart", mention.chunkOffsetStart());
            row.put("chunkOffsetEnd", mention.chunkOffsetEnd());
            row.put("documentOffsetStart", mention.documentOffsetStart());
            row.put("documentOffsetEnd", mention.documentOffsetEnd());
            row.put("surfaceForm", mention.surfaceForm());
            row.put("proposedType", mention.proposedType());
            row.put("extractedAt", mention.extractedAt() == null ? null : mention.extractedAt().toString());
            rows.add(row);
        }
        cypher.write("""
                UNWIND $rows AS row
                MERGE (n:ExtractedMention {tenant_id: $tenant, mention_id: row.mentionId})
                SET n.source_version_id = row.sourceVersionId,
                    n.content_ref_id = row.contentRefId,
                    n.chunk_id = row.chunkId,
                    n.chunk_offset_start = row.chunkOffsetStart,
                    n.chunk_offset_end = row.chunkOffsetEnd,
                    n.document_offset_start = row.documentOffsetStart,
                    n.document_offset_end = row.documentOffsetEnd,
                    n.surface_form = row.surfaceForm,
                    n.proposed_type = row.proposedType,
                    n.extracted_at = row.extractedAt
                """, Map.of("tenant", tenant, "rows", rows));
        return countByIds(tenant, rows, "mentionId",
                "MATCH (n:ExtractedMention {tenant_id: $tenant, mention_id: id}) RETURN count(n) AS c");
    }

    @Override
    public int upsertRelations(List<ExtractedRelation> batch) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        ensureExtractedSchema();
        String tenant = batch.getFirst().tenantId();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExtractedRelation relation : batch) {
            if (!tenant.equals(relation.tenantId())) {
                throw new IllegalArgumentException("all extracted relations in a write must share a tenant");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("relationId", relation.relationId());
            row.put("sourceVersionId", relation.sourceVersionId());
            row.put("predicate", relation.predicate());
            row.put("subjectMentionId", relation.subjectMentionId());
            row.put("objectMentionId", relation.objectMentionId());
            row.put("evidenceChunkId", relation.evidenceChunkId());
            row.put("evidenceChunkOffsetStart", relation.evidenceChunkOffsetStart());
            row.put("evidenceChunkOffsetEnd", relation.evidenceChunkOffsetEnd());
            row.put("evidenceDocumentOffsetStart", relation.evidenceDocumentOffsetStart());
            row.put("evidenceDocumentOffsetEnd", relation.evidenceDocumentOffsetEnd());
            row.put("validatedAt", relation.validatedAt() == null ? null : relation.validatedAt().toString());
            rows.add(row);
        }
        cypher.write("""
                UNWIND $rows AS row
                MATCH (s:ExtractedMention {tenant_id: $tenant, mention_id: row.subjectMentionId})
                MATCH (o:ExtractedMention {tenant_id: $tenant, mention_id: row.objectMentionId})
                MERGE (s)-[e:EXTRACTED_RELATION {tenant_id: $tenant, relation_id: row.relationId}]->(o)
                SET e.source_version_id = row.sourceVersionId,
                    e.predicate = row.predicate,
                    e.evidence_chunk_id = row.evidenceChunkId,
                    e.evidence_chunk_offset_start = row.evidenceChunkOffsetStart,
                    e.evidence_chunk_offset_end = row.evidenceChunkOffsetEnd,
                    e.evidence_document_offset_start = row.evidenceDocumentOffsetStart,
                    e.evidence_document_offset_end = row.evidenceDocumentOffsetEnd,
                    e.validated_at = row.validatedAt
                """, Map.of("tenant", tenant, "rows", rows));
        return countByIds(tenant, rows, "relationId",
                "MATCH ()-[e:EXTRACTED_RELATION {tenant_id: $tenant, relation_id: id}]->() RETURN count(e) AS c");
    }

    private int countByIds(String tenant, List<Map<String, Object>> rows, String idField, String query) {
        List<String> ids = rows.stream().map(row -> String.valueOf(row.get(idField))).distinct().toList();
        List<Map<String, Object>> countRows = cypher.read(
                "UNWIND $ids AS id " + query, Map.of("tenant", tenant, "ids", ids));
        Object count = countRows.isEmpty() ? 0 : countRows.getFirst().get("c");
        return count instanceof Number number ? number.intValue() : 0;
    }

    private void ensureExtractedSchema() {
        if (extractedSchemaEnsured) {
            return;
        }
        synchronized (this) {
            if (extractedSchemaEnsured) {
                return;
            }
            cypher.write("""
                    CREATE CONSTRAINT extracted_mention_unique IF NOT EXISTS
                    FOR (m:ExtractedMention) REQUIRE (m.tenant_id, m.mention_id) IS UNIQUE
                    """, Map.of());
            cypher.write("""
                    CREATE CONSTRAINT resolution_slot_unique IF NOT EXISTS
                    FOR (s:ResolutionSlot) REQUIRE (s.tenant_id, s.mention_id) IS UNIQUE
                    """, Map.of());
            cypher.write("""
                    CREATE CONSTRAINT relation_evidence_unique IF NOT EXISTS
                    FOR (e:RelationEvidence) REQUIRE e.evidence_id IS UNIQUE
                    """, Map.of());
            cypher.write("""
                    CREATE CONSTRAINT canonical_relation_anchor_unique IF NOT EXISTS
                    FOR (r:CanonicalRelationAnchor) REQUIRE r.canonical_relation_id IS UNIQUE
                    """, Map.of());
            cypher.write("""
                    CREATE CONSTRAINT extracted_relation_unique IF NOT EXISTS
                    FOR (r:ExtractedRelation) REQUIRE (r.tenant_id, r.relation_id) IS UNIQUE
                    """, Map.of());
            cypher.write("""
                    CREATE CONSTRAINT entity_identity_unique IF NOT EXISTS
                    FOR (e:Entity) REQUIRE (e.tenant, e.id) IS UNIQUE
                    """, Map.of());
            extractedSchemaEnsured = true;
        }
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
    public java.util.Optional<EntityNeighborhood> neighborhood(String tenant, String entityId, int limit) {
        if (tenant == null || tenant.isBlank() || entityId == null || entityId.isBlank()) {
            return java.util.Optional.empty();
        }
        List<Map<String, Object>> center = cypher.read("""
                MATCH (c:Entity {tenant: $tenant, id: $id})
                RETURN c.id AS id, c.label AS label, c.type AS type
                LIMIT 1
                """, Map.of("tenant", tenant, "id", entityId));
        if (center.isEmpty()) {
            return java.util.Optional.empty();
        }
        ensureExtractedSchema();
        int maxNeighbors = Math.min(50, Math.max(1, limit));
        // Canonical Entity identity is (tenant, id); the unique constraint backs
        // this start-node match. Every traversed REL and neighbor is tenant-scoped.
        List<Map<String, Object>> rows = cypher.read("""
                MATCH (c:Entity {tenant: $tenant, id: $id})
                CALL {
                    WITH c
                    MATCH (c)-[r:REL {tenant_id: $tenant}]->(n:Entity {tenant: $tenant})
                    RETURN 'OUTGOING' AS direction,
                           coalesce(r.predicate, r.verb) AS relation,
                           r.canonical_relation_id AS canonical_relation_id,
                           n.id AS nid, n.label AS nlabel, n.type AS ntype
                    UNION ALL
                    WITH c
                    MATCH (n:Entity {tenant: $tenant})-[r:REL {tenant_id: $tenant}]->(c)
                    RETURN 'INCOMING' AS direction,
                           coalesce(r.predicate, r.verb) AS relation,
                           r.canonical_relation_id AS canonical_relation_id,
                           n.id AS nid, n.label AS nlabel, n.type AS ntype
                }
                RETURN direction, relation, canonical_relation_id, nid, nlabel, ntype
                LIMIT $limit
                """, Map.of("tenant", tenant, "id", entityId, "limit", maxNeighbors + 1));
        boolean truncated = rows.size() > maxNeighbors;
        List<Map<String, Object>> kept = rows.stream().limit(maxNeighbors).toList();
        List<EntityNeighborhood.Neighbor> neighbors = new ArrayList<>(kept.size());
        Map<String, String> relationByCanonicalId = new LinkedHashMap<>();
        Map<String, String> neighborByCanonicalId = new LinkedHashMap<>();
        for (Map<String, Object> row : kept) {
            String nid = row.get("nid") == null ? null : String.valueOf(row.get("nid"));
            String relation = row.get("relation") == null ? null : String.valueOf(row.get("relation"));
            String canonicalId = row.get("canonical_relation_id") == null ? null
                    : String.valueOf(row.get("canonical_relation_id"));
            neighbors.add(new EntityNeighborhood.Neighbor(
                    nid,
                    row.get("nlabel") == null ? null : String.valueOf(row.get("nlabel")),
                    row.get("ntype") == null ? null : String.valueOf(row.get("ntype")),
                    relation,
                    String.valueOf(row.get("direction")),
                    canonicalId));
            if (canonicalId != null) {
                relationByCanonicalId.putIfAbsent(canonicalId, relation);
                neighborByCanonicalId.putIfAbsent(canonicalId, nid);
            }
        }

        List<EntityNeighborhood.Evidence> evidence = new ArrayList<>();
        if (!relationByCanonicalId.isEmpty()) {
            List<Map<String, Object>> evidenceRows = cypher.read("""
                    MATCH (ev:RelationEvidence {tenant_id: $tenant})
                    WHERE ev.canonical_relation_id IN $ids
                    RETURN ev.canonical_relation_id AS canonical_relation_id,
                           ev.evidence_chunk_id AS chunk_id,
                           ev.source_version_id AS source_version,
                           ev.evidence_offset_start AS offset_start,
                           ev.evidence_offset_end AS offset_end
                    LIMIT 200
                    """, Map.of("tenant", tenant, "ids", relationByCanonicalId.keySet().stream().toList()));
            for (Map<String, Object> row : evidenceRows) {
                String canonicalId = row.get("canonical_relation_id") == null ? null
                        : String.valueOf(row.get("canonical_relation_id"));
                evidence.add(new EntityNeighborhood.Evidence(
                        canonicalId == null ? null : relationByCanonicalId.get(canonicalId),
                        canonicalId == null ? null : neighborByCanonicalId.get(canonicalId),
                        row.get("chunk_id") == null ? null : String.valueOf(row.get("chunk_id")),
                        row.get("source_version") == null ? null
                                : String.valueOf(row.get("source_version")),
                        toInt(row.get("offset_start")),
                        toInt(row.get("offset_end")),
                        canonicalId));
            }
        }
        Map<String, Object> entity = center.getFirst();
        return java.util.Optional.of(new EntityNeighborhood(
                String.valueOf(entity.get("id")),
                entity.get("label") == null ? null : String.valueOf(entity.get("label")),
                entity.get("type") == null ? null : String.valueOf(entity.get("type")),
                neighbors, evidence, truncated));
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

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
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
