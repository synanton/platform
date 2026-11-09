package org.synanton.synquest.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.common.jwt.ServiceTokenMinter;
import org.synanton.common.resolution.CanonicalEntityLookup;
import org.synanton.common.resolution.ExactTypeCompatibility;
import org.synanton.common.resolution.ResolutionPolicy;
import org.synanton.common.resolution.TypeCompatibility;
import org.synanton.synquest.config.ResolutionConsumerProperties;
import org.synanton.synquest.config.ResolutionPolicyProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for resolution candidate retrieval (PR #4b.2). */
@Configuration
@EnableConfigurationProperties({ResolutionPolicyProperties.class, ResolutionConsumerProperties.class})
public class ResolutionConfig {

    @Bean
    public CanonicalEntityLookup canonicalEntityLookup(
            @Value("${RELIX_BASE_URL:http://relix:8080}") String relixBaseUrl,
            @Value("${SYNANTON_JWT_SECRET}") String jwtSecret) {
        return new HttpRelixCanonicalEntityLookup(relixBaseUrl,
                new ServiceTokenMinter(jwtSecret), new ObjectMapper());
    }

    @Bean
    public ResolutionWriteClient resolutionWriteClient(
            @Value("${RELIX_BASE_URL:http://relix:8080}") String relixBaseUrl,
            @Value("${SYNANTON_JWT_SECRET}") String jwtSecret) {
        return new HttpRelixResolutionWriter(relixBaseUrl, new ServiceTokenMinter(jwtSecret));
    }

    @Bean
    public TypeCompatibility typeCompatibility() {
        return new ExactTypeCompatibility();
    }

    @Bean
    public ResolutionPolicy resolutionPolicy(ResolutionPolicyProperties properties,
                                             TypeCompatibility typeCompatibility) {
        return new DefaultResolutionPolicy(properties.policyVersionOrDefault(),
                properties.absoluteThresholdOrDefault(), properties.marginThresholdOrDefault(),
                typeCompatibility);
    }
}
