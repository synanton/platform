package org.synanton.relix.api;

import java.util.Map;
import org.synanton.common.resolution.CanonicalEntityLookup;
import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.common.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Read-only source-of-truth verification for a Lucene entity candidate. */
@RestController
public class CanonicalEntityController {

    private final CanonicalEntityLookup entityLookup;

    public CanonicalEntityController(CanonicalEntityLookup entityLookup) {
        this.entityLookup = entityLookup;
    }

    @GetMapping("/v1/entities/{entityId}/type")
    public ResponseEntity<Map<String, String>> activeType(@PathVariable String entityId) {
        TenantContext context = TenantContext.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            throw new MissingTenantContextException();
        }
        return entityLookup.getActiveEntityType(context.tenantId(), entityId)
                .map(type -> ResponseEntity.ok(Map.of("entity_id", entityId, "type", type)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
