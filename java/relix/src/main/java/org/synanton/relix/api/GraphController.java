package org.synanton.relix.api;

import jakarta.servlet.http.HttpServletRequest;
import org.synanton.relix.api.dto.*;
import org.synanton.relix.service.GraphQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class GraphController {

    private final GraphQueryService graphQueryService;

    public GraphController(GraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
    }

    @PostMapping("/graph/query")
    public GraphQueryResponse query(@RequestBody GraphQueryRequest req, HttpServletRequest httpReq) {
        String tenant = requireTenant(req.tenant(), httpReq);
        GraphQueryRequest effective = req.tenant() != null ? req
                : new GraphQueryRequest(tenant, req.shape(), req.params());
        return graphQueryService.query(effective);
    }

    /**
     * Body tenant wins; otherwise the verified/mock context attribute; never a
     * demo default. A missing tenant rejects (fail closed).
     */
    private static String requireTenant(String bodyTenant, HttpServletRequest request) {
        if (bodyTenant != null && !bodyTenant.isBlank()) {
            return bodyTenant;
        }
        Object attr = request.getAttribute("tenant");
        String tenant = attr instanceof String s && !s.isBlank() ? s : null;
        if (tenant == null) {
            throw new org.synanton.common.tenant.MissingTenantContextException();
        }
        return tenant;
    }

    @PostMapping("/graph/rebuild")
    public ResponseEntity<Map<String, String>> rebuild(
            @RequestParam(required = false) String tenant,
            HttpServletRequest httpReq) {
        String effectiveTenant = requireTenant(tenant, httpReq);
        graphQueryService.rebuild(effectiveTenant);
        return ResponseEntity.ok(Map.of("status", "done", "tenant", effectiveTenant));
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ready");
    }

    @GetMapping("/graph/stats")
    public GraphStats stats(@RequestParam(required = false) String tenant,
                             HttpServletRequest httpReq) {
        return graphQueryService.stats(requireTenant(tenant, httpReq));
    }
}
