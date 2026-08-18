package org.synanton.common.security.sanitizer;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.http.converter.json.Jackson2ObjectMapperBuilder")
@EnableConfigurationProperties(SanitizerConfigurationProperties.class)
public class SanitizerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HtmlSanitizerPolicyFactory htmlSanitizerPolicyFactory(SanitizerConfigurationProperties props) {
        return new HtmlSanitizerPolicyFactory(props.toSanitizerProperties());
    }

    @Bean
    @ConditionalOnMissingBean
    SanitizingModule sanitizingModule(HtmlSanitizerPolicyFactory factory, MeterRegistry meterRegistry) {
        return new SanitizingModule(factory, meterRegistry);
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer sanitizingCustomizer(
            SanitizingModule module,
            SanitizerConfigurationProperties props
    ) {
        return builder -> {
            if (props.isEnabled()) {
                // Install alongside Boot's JavaTimeModule; modules() would replace it.
                builder.modulesToInstall(module);
            }
        };
    }
}
