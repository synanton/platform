package org.synanton.gateway.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.gateway.domain.GatewayStats;
import org.synanton.gateway.domain.QueryRequest;
import org.synanton.gateway.domain.QueryResponse;
import org.synanton.gateway.service.QueryService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/query")
    public QueryResponse query(
            @RequestBody QueryRequest request,
            HttpServletRequest httpRequest
    ) {
        // If tenant not in body, fall back to request attribute set by MockTenantFilter
        String tenant = request.tenant();
        if (tenant == null || tenant.isBlank()) {
            Object attr = httpRequest.getAttribute("tenant");
            tenant = attr != null ? (String) attr : "demo";
        }
        QueryRequest enriched = new QueryRequest(tenant, request.query(), request.topK(), request.hints());
        return queryService.query(enriched);
    }

    @GetMapping("/gateway/stats")
    public GatewayStats stats() {
        return queryService.stats();
    }
}
