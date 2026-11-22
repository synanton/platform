package org.synanton.relix.api;

import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.common.tenant.TenantContext;
import org.synanton.common.tenant.TenantIdentityMismatchException;
import org.synanton.relix.identity.ProjectionProvisioner;
import org.synanton.relix.identity.ProjectionProvisioningOutcome;
import org.synanton.relix.identity.ProjectionProvisioningResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProjectionAdminController {

    private final ProjectionProvisioner provisioner;

    public ProjectionAdminController(ProjectionProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @PostMapping("/v1/admin/tenants/{tenantId}/provision")
    public ResponseEntity<ProjectionProvisioningResult> provision(@PathVariable String tenantId) {
        TenantContext context = TenantContext.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            throw new MissingTenantContextException();
        }
        if (!context.tenantId().equals(tenantId)) {
            throw new TenantIdentityMismatchException("provisioning tenant does not match verified identity");
        }
        if (!context.hasRole("admin") && !context.hasRole("provisioner")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "provisioning requires admin or provisioner role");
        }
        ProjectionProvisioningResult result = provisioner.provision(tenantId);
        if (result.outcome() == ProjectionProvisioningOutcome.FAILED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
        }
        return ResponseEntity.ok(result);
    }
}
