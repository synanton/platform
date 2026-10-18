package org.synanton.synquest.config;

import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Search-plane identity: every business path requires a verified Bearer JWT; the
 * tenant and principal come only from the verified context (frozen search-model
 * §42 — never from the body or query).
 *
 * <p>/health is the one anonymous exception (EnvelopeExemptRoutes spirit): it
 * carries no tenant data and exists so k8s readiness probes — which cannot
 * authenticate — can gate traffic. Business readiness per tenant is
 * GET /v1/search-projection, which stays behind the JWT.
 */
@Configuration
public class SynquestSecurityConfig {

    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilter(
            @Value("${SYNANTON_JWT_SECRET}") String jwtSecret) {
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(jwtSecret), "", "/health");
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
