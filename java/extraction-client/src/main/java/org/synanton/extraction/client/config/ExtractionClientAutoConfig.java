package org.synanton.extraction.client.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.synanton.extraction.client.ExtractionClientMetrics;
import org.synanton.extraction.client.ExtractionClientProperties;
import org.synanton.extraction.client.ExtractionPlaneClient;
import org.synanton.extraction.client.LocalTikaFallbackExtractor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ExtractionClientProperties.class)
public class ExtractionClientAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public ExtractionClientMetrics extractionClientMetrics(MeterRegistry meterRegistry) {
        return new ExtractionClientMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExtractionPlaneClient extractionPlaneClient(
            ExtractionClientProperties properties,
            ExtractionClientMetrics metrics) {
        return new ExtractionPlaneClient(properties, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public LocalTikaFallbackExtractor localTikaFallbackExtractor() {
        return new LocalTikaFallbackExtractor();
    }
}
