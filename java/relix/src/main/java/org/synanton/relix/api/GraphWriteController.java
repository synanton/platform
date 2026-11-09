package org.synanton.relix.api;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.common.tenant.TenantContext;
import org.synanton.relix.graph.ExtractedMention;
import org.synanton.relix.graph.ExtractedRelation;
import org.synanton.relix.graph.CanonicalRelationPromotionPort;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.common.resolution.ActiveResolution;
import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal RAKG graph-write seam. Writes only :ExtractedMention and
 * :EXTRACTED_RELATION; never canonical :Entity/:REL. The tenant MUST come from
 * the verified JWT filter; the request body can only confirm it, never choose it.
 */
@RestController
public class GraphWriteController {

    private final GraphConnector graphConnector;
    private final CanonicalRelationPromotionPort relationPromoter;

    public GraphWriteController(GraphConnector graphConnector,
                                CanonicalRelationPromotionPort relationPromoter) {
        this.graphConnector = graphConnector;
        this.relationPromoter = relationPromoter;
    }

    @PostMapping("/graph/mentions")
    public Map<String, Object> upsertMentions(@RequestBody MentionsRequest body) {
        List<ExtractedMention> mentions = body == null ? List.of() : body.mentions();
        String tenant = requireVerifiedTenant(mentions.stream().map(ExtractedMention::tenantId).toList());
        int persisted = graphConnector.upsertMentions(mentions);
        return Map.of("status", "ok", "count", persisted, "tenant", tenant);
    }

    @PostMapping("/graph/relations")
    public Map<String, Object> upsertRelations(@RequestBody RelationsRequest body) {
        List<ExtractedRelation> relations = body == null ? List.of() : body.relations();
        String tenant = requireVerifiedTenant(relations.stream().map(ExtractedRelation::tenantId).toList());
        int persisted = graphConnector.upsertRelations(relations);
        return Map.of("status", "ok", "count", persisted, "tenant", tenant);
    }

    @PostMapping("/graph/resolutions")
    public Map<String, Object> upsertResolution(@RequestBody ResolutionDecision decision) {
        String tenant = requireVerifiedTenant(List.of(
                decision == null || decision.tenantId() == null ? "" : decision.tenantId()));
        graphConnector.upsertResolution(decision);
        return Map.of("status", "ok", "mention_id",
                String.valueOf(decision.mentionId()), "active",
                decision.outcome() == ResolutionOutcome.RESOLVED);
    }

    @PostMapping("/graph/promotions")
    public Map<String, Object> promoteRelation(@RequestBody CanonicalRelation relation) {
        String tenant = requireVerifiedTenant(java.util.List.of(
                relation == null || relation.tenantId() == null ? "" : relation.tenantId()));
        try {
            var result = relationPromoter.promoteRelation(relation);
            return Map.of("status", "ok", "tenant", tenant,
                    "relations_created", result.relationsCreated(),
                    "evidence_created", result.evidenceCreated(),
                    "evidence_matched", result.evidenceMatched());
        } catch (IllegalStateException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/v1/resolutions/active")
    public java.util.List<ActiveResolution> activeResolutions(@RequestBody ActiveResolutionsRequest request) {
        String tenant = requireVerifiedTenant(java.util.List.of());
        return graphConnector.getActiveResolutions(tenant,
                request == null || request.mentionIds() == null ? java.util.List.of() : request.mentionIds());
    }

    @org.springframework.web.bind.annotation.GetMapping("/v1/extracted-relations/{relationId}")
    public org.springframework.http.ResponseEntity<?> extractedRelation(
            @org.springframework.web.bind.annotation.PathVariable String relationId) {
        String tenant = requireVerifiedTenant(java.util.List.of());
        return graphConnector.getExtractedRelation(tenant, relationId)
                .<org.springframework.http.ResponseEntity<?>>map(org.springframework.http.ResponseEntity::ok)
                .orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build());
    }

    public record ActiveResolutionsRequest(java.util.List<String> mentionIds) {}

    private static String requireVerifiedTenant(List<String> bodyTenants) {
        TenantContext context = TenantContext.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            throw new MissingTenantContextException();
        }
        String tenant = context.tenantId();
        Set<String> distinct = new HashSet<>(bodyTenants);
        distinct.remove(null);
        distinct.remove("");
        if (distinct.size() > 1 || (!distinct.isEmpty() && !distinct.contains(tenant))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "body tenant must match the verified tenant");
        }
        return tenant;
    }

    public record MentionsRequest(List<ExtractedMention> mentions) {}

    public record RelationsRequest(List<ExtractedRelation> relations) {}
}
