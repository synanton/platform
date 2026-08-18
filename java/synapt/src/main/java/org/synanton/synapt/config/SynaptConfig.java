package org.synanton.synapt.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.synanton.synapt.client.GatewayClient;
import org.synanton.synapt.filter.DeprecationWarningFilter;
import org.synanton.synapt.filter.SecurityHeadersFilter;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@EnableConfigurationProperties(SynaptProperties.class)
public class SynaptConfig {

    @Bean
    public GatewayClient gatewayClient(SynaptProperties props) {
        WebClient wc = WebClient.builder().baseUrl(props.gateway().baseUrl()).build();
        return new GatewayClient(wc, props.gateway().timeoutMs());
    }

    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter(SynaptProperties props) {
        FilterRegistrationBean<SecurityHeadersFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new SecurityHeadersFilter(props.uiSecurity()));
        bean.addUrlPatterns("/*");
        bean.setOrder(1);
        return bean;
    }

    @Bean
    DeprecationWarningFilter deprecationWarningFilter(MeterRegistry meterRegistry) {
        return new DeprecationWarningFilter(meterRegistry);
    }
}
