package org.synanton.synapt.api;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.common.tenant.TenantContext;
import org.synanton.synapt.client.GatewayClient;
import org.synanton.synapt.domain.SearchRequest;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final GatewayClient gatewayClient;

    public SearchController(GatewayClient gatewayClient) {
        this.gatewayClient = gatewayClient;
    }

    @PostMapping("/search")
    public ResponseEntity<Object> search(
            @Valid @RequestBody SearchRequest request,
            HttpServletRequest httpRequest
    ) {
        TenantContext ctx = TenantContext.get();
        String tenant = ctx != null ? ctx.tenantId() : "demo";
        String traceId = MDC.get("traceId");
        log.info("search tenant={} query='{}' topK={} traceId={}", tenant, request.query(), request.effectiveTopK(), traceId);

        Object response = gatewayClient.query(request, tenant);
        return ResponseEntity.ok(response);
    }
}
