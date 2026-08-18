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
        String tenant = req.tenant() != null ? req.tenant() : (String) httpReq.getAttribute("tenant");
        GraphQueryRequest effective = req.tenant() != null ? req
                : new GraphQueryRequest(tenant, req.shape(), req.params());
        return graphQueryService.query(effective);
    }

    @PostMapping("/graph/rebuild")
    public ResponseEntity<Map<String, String>> rebuild(
            @RequestParam(defaultValue = "demo") String tenant,
            HttpServletRequest httpReq) {
        String effectiveTenant = tenant.equals("demo") ? (String) httpReq.getAttribute("tenant") : tenant;
        if (effectiveTenant == null) effectiveTenant = "demo";
        graphQueryService.rebuild(effectiveTenant);
        return ResponseEntity.ok(Map.of("status", "done", "tenant", effectiveTenant));
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ready");
    }

    @GetMapping("/graph/stats")
    public GraphStats stats(@RequestParam(defaultValue = "demo") String tenant,
                             HttpServletRequest httpReq) {
        String effectiveTenant = (String) httpReq.getAttribute("tenant");
        if (effectiveTenant == null) effectiveTenant = tenant;
        return graphQueryService.stats(effectiveTenant);
    }
}
