package org.synanton.synquest.demo;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.synanton.common.tenant.TenantContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.service.KnowledgeSearchBackend;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Demo-only attribution endpoint. Not a production search contract. */
@RestController
public class DemoSearchController {

    private final DemoSearchService service;
    private final KnowledgeSearchBackend backend;

    public DemoSearchController(DemoSearchService service, KnowledgeSearchBackend backend) {
        this.service = service;
        this.backend = backend;
    }

    @PostMapping("/v1/demo/search")
    public ResponseEntity<?> search(@RequestBody DemoSearchRequest request) {
        TenantScope tenant = verifiedScope();
        String query = request.query();
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(new Error("VALIDATION_ERROR", "query is required"));
        }
        if (!backend.hasCanonicalData(tenant.tenantId())) {
            return ResponseEntity.notFound().build();
        }
        if (!backend.isReady(tenant.tenantId())) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        try {
            return ResponseEntity.ok(service.search(tenant.tenantId(), query));
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage(), unavailable);
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "demo search failed", failure);
        }
    }

    private static TenantScope verifiedScope() {
        TenantContext context = TenantContext.get();
        if (context == null) {
            throw new IllegalStateException("Verified tenant context is required");
        }
        return TenantScope.of(context.requireTenantId());
    }

    public record DemoSearchRequest(@JsonProperty("query") String query) {}
    public record Error(String code, String detail) {}
}
