package org.synanton.synflux.config;

import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class SynfluxSecurityConfig {

    private static final String[] ANONYMOUS_PATHS = {"/health"};
    private static final String[] ANONYMOUS_GET_ACTUATOR_PATHS = {
        "/actuator/health", "/actuator/info", "/actuator/prometheus"
    };

    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilter(
            @Value("${SYNANTON_JWT_SECRET}") String jwtSecret) {
        // /health is anonymous for probes. Only these exact GET actuator paths
        // are anonymous; no /actuator prefix and no non-GET method is skipped.
        TenantContextFilter filter = new TenantContextFilter(
            new JwtVerifier(jwtSecret), "", ANONYMOUS_PATHS, ANONYMOUS_GET_ACTUATOR_PATHS);
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
