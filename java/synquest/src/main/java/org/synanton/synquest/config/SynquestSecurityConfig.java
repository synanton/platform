package org.synanton.synquest.config;

import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Search-plane identity: every path requires a verified Bearer JWT; the tenant
 * and principal come only from the verified context (frozen search-model §42 —
 * never from the body or query).
 */
@Configuration
public class SynquestSecurityConfig {

    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilter(
            @Value("${SYNANTON_JWT_SECRET}") String jwtSecret) {
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(jwtSecret), "");
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
