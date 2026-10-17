package org.synanton.security.envelope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeExemptRoutesTest {

    private static final Set<EnvelopeExemptRoutes.RouteKey> EXPECTED = Set.of(
        new EnvelopeExemptRoutes.RouteKey("GET", "/health"),
        new EnvelopeExemptRoutes.RouteKey("GET", "/readyz"),
        new EnvelopeExemptRoutes.RouteKey("GET", "/livez"),
        new EnvelopeExemptRoutes.RouteKey("GET", "/metrics"),
        new EnvelopeExemptRoutes.RouteKey("GET", "/actuator/health"),
        new EnvelopeExemptRoutes.RouteKey("GET", "/actuator/info"),
        new EnvelopeExemptRoutes.RouteKey("GET", "/actuator/prometheus")
    );

    @Test
    void allowlistMatchesExpectedSetExactly() {
        assertThat(EnvelopeExemptRoutes.enumerated())
            .as("production allowlist must equal the expected set — "
                + "additions, removals, and renames are all failures")
            .isEqualTo(EXPECTED);
    }

    @ParameterizedTest
    @MethodSource("expectedRoutes")
    void enumeratedRoute_isExempt(EnvelopeExemptRoutes.RouteKey route) {
        assertThat(EnvelopeExemptRoutes.isExempt(route.method(), route.path())).isTrue();
    }

    @ParameterizedTest
    @MethodSource("expectedRoutes")
    void enumeratedRoute_isExempt_caseInsensitiveMethod(EnvelopeExemptRoutes.RouteKey route) {
        assertThat(EnvelopeExemptRoutes.isExempt(route.method().toLowerCase(), route.path()))
            .isTrue();
    }

    static Stream<EnvelopeExemptRoutes.RouteKey> expectedRoutes() {
        return EXPECTED.stream();
    }

    @Test
    void businessRoute_isNotExempt() {
        assertThat(EnvelopeExemptRoutes.isExempt("POST", "/search")).isFalse();
        assertThat(EnvelopeExemptRoutes.isExempt("GET", "/search")).isFalse();
    }

    @Test
    void postMcp_isNotExempt() {
        assertThat(EnvelopeExemptRoutes.isExempt("POST", "/mcp")).isFalse();
    }

    @Test
    void getMcp_isNotExemptHere() {
        assertThat(EnvelopeExemptRoutes.isExempt("GET", "/mcp")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/actuator/env",
        "/actuator/beans",
        "/actuator/threaddump",
        "/actuator/heapdump",
        "/actuator/loggers",
        "/actuator/mappings"
    })
    void unknownActuatorSubpath_isNotExempt(String path) {
        assertThat(EnvelopeExemptRoutes.isExempt("GET", path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/health/../search",
        "/health;jsessionid=x",
        "/./health",
        "//health",
        "/health/",
        "/HEALTH"
    })
    void ambiguousOrMalformedPath_isNotExempt(String path) {
        assertThat(EnvelopeExemptRoutes.isExempt("GET", path)).isFalse();
    }

    @Test
    void nullMethod_isNotExempt() {
        assertThat(EnvelopeExemptRoutes.isExempt(null, "/health")).isFalse();
    }

    @Test
    void nullPath_isNotExempt() {
        assertThat(EnvelopeExemptRoutes.isExempt("GET", null)).isFalse();
    }

    @Test
    void blankMethod_isNotExempt() {
        assertThat(EnvelopeExemptRoutes.isExempt("", "/health")).isFalse();
        assertThat(EnvelopeExemptRoutes.isExempt("   ", "/health")).isFalse();
    }

    @Test
    void blankPath_isNotExempt() {
        assertThat(EnvelopeExemptRoutes.isExempt("GET", "")).isFalse();
        assertThat(EnvelopeExemptRoutes.isExempt("GET", "   ")).isFalse();
    }

    @Test
    void routeKey_rejectsRelativePath() {
        assertThatThrownBy(() -> new EnvelopeExemptRoutes.RouteKey("GET", "health"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routeKey_rejectsBlankMethod() {
        assertThatThrownBy(() -> new EnvelopeExemptRoutes.RouteKey("", "/health"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
