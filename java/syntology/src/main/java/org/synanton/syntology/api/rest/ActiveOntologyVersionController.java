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

/** Internal service lookup used by Relix to bootstrap canonical graph metadata. */
@RestController
@RequestMapping("/v1/tenants")
public class ActiveOntologyVersionController {

    private static final Logger log = LoggerFactory.getLogger(ActiveOntologyVersionController.class);

    private final OntologyService ontologyService;

    public ActiveOntologyVersionController(OntologyService ontologyService) {
        this.ontologyService = ontologyService;
    }

    @GetMapping("/{tenantId}/active-ontology-version")
    public ResponseEntity<?> activeVersion(@PathVariable String tenantId) {
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
            return ontologyService.activeVersion(tenantId)
                    .<ResponseEntity<?>>map(version -> ResponseEntity.ok(toResponse(tenantId, version)))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(java.util.Map.of(
                                    "error", "no_active_ontology",
                                    "tenantId", tenantId)));
        } catch (RuntimeException failure) {
            log.error("Active ontology version lookup failed tenant={}", tenantId, failure);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(java.util.Map.of("error", "syntology_unavailable"));
        }
    }

    private static ActiveOntologyVersionResponse toResponse(String tenantId, OntologyVersion version) {
        // versionId is the immutable per-activation reference available today.
        return new ActiveOntologyVersionResponse(
                tenantId,
                version.version(),
                "uuid:" + version.versionId(),
                version.createdAt());
    }

    public record ActiveOntologyVersionResponse(
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("activeVersion") String activeVersion,
            @JsonProperty("versionRef") String versionRef,
            @JsonProperty("activatedAt") Instant activatedAt) {
    }
}
