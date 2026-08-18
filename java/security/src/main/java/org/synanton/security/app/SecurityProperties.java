package org.synanton.security.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security")
public record SecurityProperties(
        Idp idp,
        Jwt jwt,
        ApiKey apiKey,
        TokenExchange tokenExchange,
        Oidc oidc
) {
    public record Idp(String backend, String htpasswdPath) {}

    public record Jwt(String secret, long expiresInSeconds, String tenantId) {}

    public record ApiKey(String prefix, Cache cache) {
        public record Cache(long ttlSeconds, long maxSize) {}
    }

    public record TokenExchange(long serviceTokenTtlSeconds) {}

    public record Oidc(boolean enabled, String issuerUri, String clientId) {}

    public SecurityProperties {
        if (apiKey == null) {
            apiKey = new ApiKey("syn_", new ApiKey.Cache(60, 10_000));
        }
        if (tokenExchange == null) {
            tokenExchange = new TokenExchange(300);
        }
        if (oidc == null) {
            oidc = new Oidc(false, "http://keycloak.security.svc/realms/synanton", "synanton");
        }
    }

    public static SecurityProperties of(Idp idp, Jwt jwt) {
        return new SecurityProperties(idp, jwt, null, null, null);
    }
}
