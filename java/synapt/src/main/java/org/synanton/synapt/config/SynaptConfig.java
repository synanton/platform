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

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "synanton.envelope.enabled", havingValue = "true", matchIfMissing = true)
    org.springframework.boot.web.servlet.FilterRegistrationBean<
            org.synanton.common.envelope.SecurityEnvelopeFilter> securityEnvelopeFilter(
            @org.springframework.beans.factory.annotation.Value("${synanton.envelope.provider-url}") String providerUrl,
            @org.springframework.beans.factory.annotation.Value("${synanton.envelope.issuer}") String issuer,
            @org.springframework.beans.factory.annotation.Value("${synanton.envelope.audience}") String audience,
            @org.springframework.beans.factory.annotation.Value("${synanton.envelope.cache-ttl-seconds}") long cacheTtlSeconds,
            @org.springframework.beans.factory.annotation.Value("${synanton.envelope.timeout-ms}") long timeoutMs) {
        var verifier = new org.synanton.common.envelope.HttpEnvelopeVerifier(
                java.net.URI.create(providerUrl), java.time.Duration.ofMillis(timeoutMs));
        var filter = new org.synanton.common.envelope.SecurityEnvelopeFilter(
                verifier, issuer, audience, java.time.Duration.ofSeconds(cacheTtlSeconds));
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
