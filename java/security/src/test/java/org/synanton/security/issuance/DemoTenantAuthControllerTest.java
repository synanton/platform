package org.synanton.security.issuance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.synanton.security.api.DemoTenantAuthController;

class DemoTenantAuthControllerTest {

    private static final String SECRET = "demo-issuer-test-secret-at-least-32-bytes";

    private MockMvc mvc(boolean auditFails) {
        List<IssuanceEvent> events = new ArrayList<>();
        DemoTenantTokenIssuer issuer = new DemoTenantTokenIssuer(
                SECRET, "synanton-demo", "synanton-local", 900,
                event -> {
                    if (auditFails) {
                        throw new IllegalStateException("audit down");
                    }
                    events.add(event);
                });
        return MockMvcBuilders.standaloneSetup(new DemoTenantAuthController(issuer)).build();
    }

    @Test
    void mintsTenantTokenForKnownClaim() throws Exception {
        mvc(false).perform(post("/v1/auth/tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principal_id\":\"svc\",\"tenant_id\":\"tenant-a\","
                                + "\"capabilities\":[\"content.reconcile\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value("tenant-a"))
                .andExpect(jsonPath("$.capabilities[0]").value("CONTENT_RECONCILE_TENANT"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void unknownClaimIsRejected() throws Exception {
        mvc(false).perform(post("/v1/auth/tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principal_id\":\"svc\",\"tenant_id\":\"tenant-a\","
                                + "\"capabilities\":[\"content.reconcile.typo\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_CAPABILITY_CLAIM"));
    }

    @Test
    void auditFailureReturns503AndNoToken() throws Exception {
        mvc(true).perform(post("/v1/auth/tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principal_id\":\"svc\",\"tenant_id\":\"tenant-a\","
                                + "\"capabilities\":[\"content.reconcile\"]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ISSUANCE_AUDIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }
}
