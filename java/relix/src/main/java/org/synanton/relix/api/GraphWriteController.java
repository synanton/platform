package org.synanton.relix.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.synanton.relix.graph.ExtractedMention;
import org.synanton.relix.graph.ExtractedRelation;
import org.synanton.relix.graph.GraphConnector;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal RAKG graph-write seam. Writes only :ExtractedMention and
 * :EXTRACTED_RELATION; never canonical :Entity/:REL. The tenant is taken from
 * the verified request attribute when present, otherwise from the body (the
 * synflux caller is internal); a missing tenant fails closed.
 */
@RestController
public class GraphWriteController {

    private final GraphConnector graphConnector;

    public GraphWriteController(GraphConnector graphConnector) {
        this.graphConnector = graphConnector;
    }

    @PostMapping("/graph/mentions")
    public Map<String, Object> upsertMentions(
            @RequestBody MentionsRequest body, HttpServletRequest request) {
        List<ExtractedMention> mentions = body == null ? List.of() : body.mentions();
        String tenant = requireTenant(mentions.stream().map(ExtractedMention::tenantId).toList(), request);
        graphConnector.upsertMentions(mentions);
        return Map.of("status", "ok", "count", mentions.size(), "tenant", tenant);
    }

    @PostMapping("/graph/relations")
    public Map<String, Object> upsertRelations(
            @RequestBody RelationsRequest body, HttpServletRequest request) {
        List<ExtractedRelation> relations = body == null ? List.of() : body.relations();
        String tenant = requireTenant(relations.stream().map(ExtractedRelation::tenantId).toList(), request);
        graphConnector.upsertRelations(relations);
        return Map.of("status", "ok", "count", relations.size(), "tenant", tenant);
    }

    private static String requireTenant(List<String> bodyTenants, HttpServletRequest request) {
        Set<String> distinct = new HashSet<>(bodyTenants);
        distinct.remove(null);
        distinct.remove("");
        if (distinct.size() > 1) {
            throw new IllegalArgumentException("all graph rows in a write must share a tenant");
        }
        String bodyTenant = distinct.isEmpty() ? null : distinct.iterator().next();
        Object attr = request.getAttribute("tenant");
        String verified = attr instanceof String value && !value.isBlank() ? value : null;
        if (verified != null && bodyTenant != null && !verified.equals(bodyTenant)) {
            throw new IllegalArgumentException("body tenant does not match the verified tenant");
        }
        String tenant = verified != null ? verified : bodyTenant;
        if (tenant == null) {
            throw new org.synanton.common.tenant.MissingTenantContextException();
        }
        return tenant;
    }

    public record MentionsRequest(List<ExtractedMention> mentions) {}

    public record RelationsRequest(List<ExtractedRelation> relations) {}
}
