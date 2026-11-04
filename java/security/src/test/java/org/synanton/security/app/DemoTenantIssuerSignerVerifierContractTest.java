package org.synanton.security.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.security.issuance.DemoTenantTokenIssuer;

/**
 * Signer↔verifier contract: the demo issuer must sign with SYNANTON_JWT_SECRET
 * (the verifier's convention), not with the @ConfigurationProperties-bound
 * security.jwt.secret. Guards against the binding-path divergence that shipped a
 * demo token synquest rejected as "Invalid JWT signature".
 */
class DemoTenantIssuerSignerVerifierContractTest {

    /** Correct secret (what synquest's @Value resolves to). */
    private static final String SYNANTON_JWT_SECRET = "contract-test-secret-at-least-32-chars-long";

    /** Divergent value the @ConfigurationProperties path binds to. */
    private static final String CONFIGURATION_PROPERTIES_SECRET = "divergent-config-properties-secret-32-chars";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AppConfig.class, TestProps.class)
            .withBean(JdbcTemplate.class, () -> {
                JdbcTemplate jdbc = mock(JdbcTemplate.class);
                when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
                return jdbc;
            })
            .withPropertyValues(
                    "SYNANTON_JWT_SECRET=" + SYNANTON_JWT_SECRET,
                    "security.jwt.secret=" + CONFIGURATION_PROPERTIES_SECRET,
                    "security.jwt.expires-in-seconds=3600",
                    "security.jwt.tenant-id=demo",
                    "security.idp.backend=htpasswd",
                    "security.idp.htpasswd-path=/tmp/nonexistent-demo-users");

    @Configuration
    @EnableConfigurationProperties(SecurityProperties.class)
    static class TestProps {}

    @Test
    void demoTenantIssuerSignsTokenThatSynquestVerifierAccepts() {
        context.run(ctx -> {
            DemoTenantTokenIssuer issuer = ctx.getBean(DemoTenantTokenIssuer.class);
            String token = issuer.issue("svc", "tenant-a", Set.of()).token();

            JwtVerifier verifier = new JwtVerifier(SYNANTON_JWT_SECRET);
            assertThat(verifier.verify(token).subject()).isEqualTo("svc");
        });
    }
}
