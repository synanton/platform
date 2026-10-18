package org.synanton.synflux.api;

import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The k8s readiness probe cannot authenticate — a JWT-guarded /health would
 * 401 and pin every pod NotReady permanently (#297's Finding 4, applied to
 * the ingestion plane). /health is anonymous; business paths are not.
 */
class HealthControllerTest {

    private static final String SECRET = "synflux-test-jwt-secret-at-least-32-bytes";

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController())
            .addFilters(new TenantContextFilter(new JwtVerifier(SECRET), "", "/health"))
            .build();

    @Test
    void healthWithoutTokenIsReachable() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
