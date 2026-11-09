package org.synanton.relix.config;

import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Graph-write endpoints require a verified HS256 tenant JWT. The read/query API
 * keeps its existing surface; only /graph/mentions and /graph/relations are
 * filtered here. A missing secret fails startup (fail closed).
 */
@Configuration
public class RelixSecurityConfig {

    @Bean
    public FilterRegistrationBean<TenantContextFilter> graphWriteTenantFilter(
            @Value("${SYNANTON_JWT_SECRET:}") String jwtSecret) {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "SYNANTON_JWT_SECRET (>=32 chars) is required for Relix graph writes");
        }
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(jwtSecret), "", "/health");
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/graph/mentions", "/graph/relations", "/graph/resolutions", "/graph/promotions",
                "/v1/entities/*", "/v1/resolutions/*", "/v1/extracted-relations/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
