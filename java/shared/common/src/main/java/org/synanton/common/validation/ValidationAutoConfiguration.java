package org.synanton.common.validation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestControllerAdvice")
@EnableConfigurationProperties(ValidationConfigurationProperties.class)
public class ValidationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ValidationProperties validationProperties(ValidationConfigurationProperties props) {
        return props.toValidationProperties();
    }
}
