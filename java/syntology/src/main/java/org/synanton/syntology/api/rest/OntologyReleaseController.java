package org.synanton.syntology.api.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.common.tenant.TenantContext;
import org.synanton.syntology.domain.model.OntologyVersion;
import org.synanton.syntology.domain.service.OntologyService;

import java.time.Instant;

/** Immutable release read path used by the extraction-contract compiler. */
@RestController
@RequestMapping("/v1/tenants")
public class OntologyReleaseController {

    private static final Logger log = LoggerFactory.getLogger(OntologyReleaseController.class);

    private final OntologyService ontologyService;

    public OntologyReleaseController(OntologyService ontologyService) {
        this.ontologyService = ontologyService;
    }

    @GetMapping("/{tenantId}/ontology-releases/{releaseId}")
    public ResponseEntity<?> release(@PathVariable String tenantId,
                                     @PathVariable String releaseId) {
        TenantContext context = TenantContext.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(java.util.Map.of("error", "unauthorized"));
        }
        if (!tenantId.equals(context.tenantId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(java.util.Map.of("error", "tenant_scope_mismatch"));
        }
        try {
            return ontologyService.release(tenantId, releaseId)
                .<ResponseEntity<?>>map(version -> ResponseEntity.ok(toResponse(tenantId, version)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(java.util.Map.of("error", "no_release", "tenantId", tenantId,
                        "releaseId", releaseId)));
        } catch (RuntimeException failure) {
            log.error("Ontology release lookup failed tenant={} release={}", tenantId, releaseId, failure);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(java.util.Map.of("error", "syntology_unavailable"));
        }
    }

    private static OntologyReleaseResponse toResponse(String tenantId, OntologyVersion version) {
        return new OntologyReleaseResponse(
            tenantId, version.ontologyId(), version.version(), version.releaseId(),
            version.releaseDigest(), version.status(), version.createdAt());
    }

    public record OntologyReleaseResponse(
        @JsonProperty("tenantId") String tenantId,
        @JsonProperty("ontologyId") String ontologyId,
        @JsonProperty("version") String version,
        @JsonProperty("releaseId") String releaseId,
        @JsonProperty("releaseDigest") String releaseDigest,
        @JsonProperty("status") String status,
        @JsonProperty("createdAt") Instant createdAt) {
    }
}
