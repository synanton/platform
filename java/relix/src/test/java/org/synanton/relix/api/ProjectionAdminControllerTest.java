package org.synanton.relix.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.SubjectAssertion;
import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.common.tenant.TenantContext;
import org.synanton.common.tenant.TenantIdentityMismatchException;
import org.synanton.relix.identity.ProjectionProvisioner;
import org.synanton.relix.identity.ProjectionProvisioningOutcome;
import org.synanton.relix.identity.ProjectionProvisioningResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectionAdminControllerTest {

    private final ProjectionProvisioner provisioner = mock(ProjectionProvisioner.class);
    private final ProjectionAdminController controller = new ProjectionAdminController(provisioner);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void provisionerRoleCanProvisionMatchingTenant() {
        TenantContext.set(assertion("tenant-a", "provisioner"));
        when(provisioner.provision("tenant-a")).thenReturn(new ProjectionProvisioningResult(
                ProjectionProvisioningOutcome.NEWLY_PROVISIONED, null, null));

        var response = controller.provision("tenant-a");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(provisioner).provision("tenant-a");
    }

    @Test
    void missingContextIsRejected() {
        assertThatThrownBy(() -> controller.provision("tenant-a"))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void mismatchedTenantIsRejected() {
        TenantContext.set(assertion("tenant-a", "admin"));

        assertThatThrownBy(() -> controller.provision("tenant-b"))
                .isInstanceOf(TenantIdentityMismatchException.class);
    }

    @Test
    void callerWithoutProvisioningRoleIsForbidden() {
        TenantContext.set(assertion("tenant-a", "user"));

        assertThatThrownBy(() -> controller.provision("tenant-a"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static SubjectAssertion assertion(String tenant, String role) {
        return new SubjectAssertion("subject", 1, List.of(), tenant,
                Instant.now().plusSeconds(60), Set.of(role), "USER_SUBJECT", "assertion");
    }
}
