package org.synanton.security.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.security.idp.ApiKeyIdentityProvider;
import org.synanton.security.idp.HtpasswdBackend;
import org.synanton.security.idp.IdentityProviderDispatcher;
import org.synanton.security.idp.IdentityProviderPort;
import org.synanton.security.idp.IdpStatusAmortizationCache;
import org.synanton.security.idp.JwtIdentityProvider;
import org.synanton.security.idp.OidcIdentityProvider;
import org.synanton.security.service.AuthService;
import org.synanton.security.service.SupportAdminService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

@Configuration
public class AppConfig {

    @Bean
    public HtpasswdBackend htpasswdBackend(SecurityProperties props) {
        return new HtpasswdBackend(props.idp().htpasswdPath());
    }

    @Bean
    public JwtVerifier jwtVerifier(SecurityProperties props) {
        return new JwtVerifier(props.jwt().secret());
    }

    @Bean
    public JwtIdentityProvider jwtIdentityProvider(JwtVerifier jwtVerifier) {
        return new JwtIdentityProvider(jwtVerifier);
    }

    @Bean
    public ApiKeyIdentityProvider apiKeyIdentityProvider(JdbcTemplate jdbc, SecurityProperties props) {
        long ttl = (props.apiKey() != null && props.apiKey().cache() != null)
                ? props.apiKey().cache().ttlSeconds() : 60;
        long maxSize = (props.apiKey() != null && props.apiKey().cache() != null)
                ? props.apiKey().cache().maxSize() : 10000;
        return new ApiKeyIdentityProvider(jdbc, ttl, maxSize);
    }

    @Bean
    public IdentityProviderDispatcher identityProviderDispatcher(
            ApiKeyIdentityProvider apiKeyProvider,
            JwtIdentityProvider jwtProvider,
            SecurityProperties props) {
        java.util.ArrayList<IdentityProviderPort> providers = new ArrayList<>();
        providers.add(apiKeyProvider);
        if (props.oidc().enabled()) {
            providers.add(new OidcIdentityProvider(props.oidc().issuerUri(), null));
        }
        providers.add(jwtProvider);
        return new IdentityProviderDispatcher(providers);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public SupportAdminService supportAdminService(JdbcTemplate jdbc, Clock clock) {
        return new SupportAdminService(jdbc, clock);
    }

    @Bean
    public IdpStatusAmortizationCache idpStatusAmortizationCache() {
        return new IdpStatusAmortizationCache(
                Map.of(
                        "STANDARD", Duration.ofSeconds(60),
                        "HIGH_SECURITY", Duration.ofSeconds(5),
                        "FINANCIAL", Duration.ofSeconds(10),
                        "HEALTHCARE", Duration.ofSeconds(10)
                ),
                100_000,
                subjectId -> new IdpStatusAmortizationCache.IdpStatus("ACTIVE", Instant.now()),
                null
        );
    }

    @Bean
    public AuthService authService(HtpasswdBackend backend, SecurityProperties props,
                                   IdentityProviderDispatcher dispatcher) {
        return new AuthService(backend, props, dispatcher);
    }
}
