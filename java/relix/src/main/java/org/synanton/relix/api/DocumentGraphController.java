package org.synanton.relix.api;

import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.common.tenant.TenantContext;
import org.synanton.relix.api.dto.DocumentGraphResponse;
import org.synanton.relix.graph.GraphConnector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Document-scoped extraction graph; document id is the persisted source-version id. */
@RestController
public class DocumentGraphController {

    private final GraphConnector graphConnector;

    public DocumentGraphController(GraphConnector graphConnector) {
        this.graphConnector = graphConnector;
    }

    @GetMapping({"/v1/documents/{documentId}/graph", "/documents/{documentId}/graph"})
    public ResponseEntity<DocumentGraphResponse> graph(@PathVariable String documentId) {
        TenantContext context = TenantContext.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            throw new MissingTenantContextException();
        }
        return ResponseEntity.ok(graphConnector.documentGraph(context.tenantId(), documentId));
    }
}
