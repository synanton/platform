package org.synanton.synquest.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.synanton.common.tenant.TenantContext;
import org.synanton.synquest.service.KnowledgeSearchBackend;
import org.synanton.synquest.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * The public search query path over canonical knowledge (frozen search-model,
 * Gen-A): POST /v1/search with {"query"}; results carry stable canonical
 * references (chunk object_id, source_version_ids) and provenance_ref.
 *
 * <p>Tenant and principal context are never read from the body — they come only
 * from the verified identity context (TenantContextFilter). Gen-A returns a
 * single server-bounded page and emits no next_page_token. Backend types and
 * topology do not leak: results carry only the frozen fields plus the additive
 * source-version references.
 */
@RestController
public class KnowledgeSearchController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchController.class);
    private static final int DEFAULT_PAGE_LIMIT = 20;

    private final KnowledgeSearchBackend backend;
    private final SearchService searchService;

    public KnowledgeSearchController(KnowledgeSearchBackend backend, SearchService searchService) {
        this.backend = backend;
        this.searchService = searchService;
    }

    @PostMapping("/v1/search")
    public ResponseEntity<?> search(
            @RequestBody SearchQueryRequest request,
            @RequestHeader(name = "X-Tenant-ID", required = false) String tenantHeader) {
        if (tenantHeader != null && !tenantHeader.isBlank()) {
            return tenantError("TENANT_HEADER_NOT_SUPPORTED",
                    "X-Tenant-ID is not supported; the tenant comes from the verified identity context");
        }
        if (request.hasTenant()) {
            return tenantError("TENANT_IN_REQUEST_BODY",
                    "tenant_id is not accepted in the request body; the tenant comes from the verified identity context");
        }
        TenantContext tenant = verifiedTenant();
        if (!backend.isReady(tenant.tenantId())) {
            return ResponseEntity.status(503).build();
        }
        try {
            List<SearchHitResource> results = backend
                    .candidates(tenant.tenantId(), request.query(), DEFAULT_PAGE_LIMIT)
                    .stream()
                    .map(candidate -> new SearchHitResource(
                            candidate.objectId(),
                            candidate.score(),
                            candidate.title(),
                            candidate.snippet(),
                            candidate.provenanceRef(),
                            List.copyOf(candidate.sourceVersionIds())))
                    .toList();
            return ResponseEntity.ok(new SearchPage(results, new SearchPageMetadata(
                    backend.strategy(), searchService.stats(tenant.tenantId()).indexGeneration())));
        } catch (IllegalStateException notReady) {
            log.warn("Search projection not ready for tenant '{}'", tenant.tenantId());
            return ResponseEntity.status(503).build();
        } catch (Exception failure) {
            log.error("Search failed for tenant '{}'", tenant.tenantId(), failure);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Rebuilds the verified tenant's projection from canonical knowledge.
     * Idempotent: re-projecting the same canonical state yields the same
     * projection (duplicate and out-of-order projection triggers converge).
     */
    @PostMapping("/v1/search-projection/rebuild")
    public ResponseEntity<ProjectionRebuildResult> rebuild() {
        TenantContext tenant = verifiedTenant();
        try {
            int chunkCount = backend.project(tenant.tenantId());
            return ResponseEntity.ok(new ProjectionRebuildResult(
                    "done", tenant.tenantId(), chunkCount, backend.strategy()));
        } catch (Exception failure) {
            log.error("Projection rebuild failed for tenant '{}'", tenant.tenantId(), failure);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Projection observability: readiness and the last build's chunk count. */
    @GetMapping("/v1/search-projection")
    public ResponseEntity<ProjectionStats> projection() {
        TenantContext tenant = verifiedTenant();
        try {
            var stats = searchService.stats(tenant.tenantId());
            return ResponseEntity.ok(new ProjectionStats(
                    backend.isReady(tenant.tenantId()), tenant.tenantId(), stats.docCount(), backend.strategy()));
        } catch (IOException failure) {
            log.error("Projection stats failed for tenant '{}'", tenant.tenantId(), failure);
            return ResponseEntity.internalServerError().build();
        }
    }

    private static ResponseEntity<ProblemDetail> tenantError(String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("code", code);
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private static TenantContext verifiedTenant() {
        TenantContext context = TenantContext.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            throw new IllegalStateException("Verified tenant context is required");
        }
        return context;
    }

    public record ProjectionStats(
            @JsonProperty("ready") boolean ready,
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("chunk_count") int chunkCount,
            @JsonProperty("strategy") String strategy) {}

    public record ProjectionRebuildResult(
            @JsonProperty("status") String status,
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("chunk_count") int chunkCount,
            @JsonProperty("strategy") String strategy) {}

    public record SearchPageMetadata(
            @JsonProperty("strategy") String strategy,
            @JsonProperty("configuration_generation") long configurationGeneration) {}

    public record SearchHitResource(
            @JsonProperty("object_id") String objectId,
            double score,
            @JsonProperty("title") String title,
            @JsonProperty("snippet") String snippet,
            @JsonProperty("provenance_ref") String provenanceRef,
            @JsonProperty("source_version_ids") List<UUID> sourceVersionIds) {}

    public record SearchPage(
            @JsonProperty("results") List<SearchHitResource> results,
            @JsonProperty("metadata") SearchPageMetadata metadata) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchQueryRequest(
            @JsonProperty("query") String query,
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("tenant") String tenant) {
        public SearchQueryRequest {
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("query is required");
            }
        }

        boolean hasTenant() {
            return (tenantId != null && !tenantId.isBlank())
                    || (tenant != null && !tenant.isBlank());
        }
    }
}
