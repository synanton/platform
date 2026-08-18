package org.synanton.synt.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.synanton.common.fs.FsPermissionGuard;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContext;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.synt.domain.port.out.OntologyAdapter;
import org.synanton.synt.infra.jena.JenaTdb2Adapter;

import java.io.IOException;

@Configuration
@EnableConfigurationProperties(SyntologyProperties.class)
public class AppConfig {

    @Bean
    OntologyAdapter ontologyAdapter(SyntologyProperties properties) {
        JenaTdb2Adapter adapter = new JenaTdb2Adapter();
        adapter.init(properties.storage().jena().path());
        return adapter;
    }

    @Bean
    public FsPermissionGuard fsPermissionGuard() {
        return new FsPermissionGuard();
    }

    @Bean
    @ConditionalOnProperty(name = "syntology.auth.enabled", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilter(SyntologyProperties props) {
        JwtVerifier verifier = new JwtVerifier(props.auth().jwtSecret());
        TenantContextFilter filter = new TenantContextFilter(
                verifier, props.tenant().defaultId(),
                "/actuator", "/capabilities", "/mcp");
        FilterRegistrationBean<TenantContextFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    @ConditionalOnProperty(name = "syntology.auth.enabled", havingValue = "false")
    public FilterRegistrationBean<OncePerRequestFilter> devTenantFilter(SyntologyProperties props) {
        String defaultTenant = props.tenant().defaultId();
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                TenantContext.setAnonymous(defaultTenant);
                try {
                    chain.doFilter(req, res);
                } finally {
                    TenantContext.clear();
                }
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
