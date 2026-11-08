package org.synanton.relix.api;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.common.tenant.TenantContext;
import org.synanton.relix.graph.ExtractedMention;
import org.synanton.relix.graph.ExtractedRelation;
import org.synanton.relix.graph.GraphConnector;
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

    public GraphWriteController(GraphConnector graphConnector) {
        this.graphConnector = graphConnector;
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
