package org.synanton.synflux.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.synanton.common.tenant.TenantContextFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ActuatorFilterSecurityTest {

    private static final String SECRET = "actuator-filter-test-secret-at-least-32-bytes";

    private final TenantContextFilter filter =
            new SynfluxSecurityConfig().tenantContextFilter(SECRET).getFilter();

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/info", "/actuator/prometheus"})
    void allowsOnlyTheThreeReadOnlyGetPaths(String path) throws Exception {
        Result result = request("GET", path);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.chainCalled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/actuator", "/actuator/env", "/actuator/shutdown", "/actuator/loggers",
        "/actuator/health/extra", "/actuator/info/extra", "/actuator/prometheus/extra"
    })
    void blocksOtherActuatorPathsEvenWithoutAToken(String path) throws Exception {
        Result result = request("GET", path);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
        assertThat(result.body()).contains("AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/info", "/actuator/prometheus"})
    void blocksNonGetMethodsOnTheReadOnlyPaths(String path) throws Exception {
        Result result = request("POST", path);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    void blocksBusinessApisWithoutAToken() throws Exception {
        Result result = request("GET", "/v1/search");

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    void blocksMutatingActuatorMethods() throws Exception {
        assertThat(request("POST", "/actuator/loggers").status()).isEqualTo(401);
        assertThat(request("POST", "/actuator/shutdown").status()).isEqualTo(401);
    }

    private Result request(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));
        return new Result(response.getStatus(), chainCalled.get(), response.getContentAsString());
    }

    private record Result(int status, boolean chainCalled, String body) {
    }
}
