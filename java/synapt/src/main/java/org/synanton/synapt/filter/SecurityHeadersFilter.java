package org.synanton.synapt.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.synanton.synapt.config.SynaptProperties;

import java.io.IOException;

public class SecurityHeadersFilter extends OncePerRequestFilter {

    public static final String CSP_ENFORCE = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; connect-src 'self'; font-src 'self'; base-uri 'self'; object-src 'none'; "
            + "form-action 'self'; frame-ancestors 'none'; require-trusted-types-for 'script'; "
            + "report-uri /csp-report; report-to csp-endpoint";

    private final SynaptProperties.UiSecurity uiSecurity;

    public SecurityHeadersFilter(SynaptProperties.UiSecurity uiSecurity) {
        this.uiSecurity = uiSecurity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String cspHeader = "enforce".equals(uiSecurity.cspMode())
                ? "Content-Security-Policy"
                : "Content-Security-Policy-Report-Only";
        response.setHeader(cspHeader, CSP_ENFORCE);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), interest-cohort=()");
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        chain.doFilter(request, response);
    }
}
