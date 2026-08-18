package org.synanton.common.grpc.validation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(name = "io.grpc.ServerInterceptor")
@EnableConfigurationProperties(GrpcValidationProperties.class)
public class PgvValidatingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PgvRuleCatalogue pgvRuleCatalogue() {
        return new PgvRuleCatalogue();
    }

    @Bean
    @ConditionalOnMissingBean
    PgvValidatingServerInterceptor pgvValidatingServerInterceptor(
            PgvRuleCatalogue catalogue,
            MeterRegistry meterRegistry,
            GrpcValidationProperties properties
    ) {
        return new PgvValidatingServerInterceptor(
                catalogue,
                meterRegistry,
                properties.isEnabled(),
                properties.isStrict()
        );
    }
}
