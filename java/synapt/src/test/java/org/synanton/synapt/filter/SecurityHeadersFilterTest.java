package org.synanton.synapt.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.synanton.synapt.config.SynaptProperties;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    @Test
    void shouldEmitEnforceCspAndCompanionHeaders() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(new SynaptProperties.UiSecurity("enforce"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/search"), response, new MockFilterChain());
        assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'self'");
        assertThat(response.getHeader("Content-Security-Policy-Report-Only")).isNull();
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Strict-Transport-Security")).contains("max-age=31536000");
    }
}
