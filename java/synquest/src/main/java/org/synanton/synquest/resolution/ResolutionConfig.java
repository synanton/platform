package org.synanton.synquest.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.common.jwt.ServiceTokenMinter;
import org.synanton.common.resolution.CanonicalEntityLookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for resolution candidate retrieval (PR #4b.2). */
@Configuration
public class ResolutionConfig {

    @Bean
    public CanonicalEntityLookup canonicalEntityLookup(
            @Value("${RELIX_BASE_URL:http://relix:8080}") String relixBaseUrl,
            @Value("${SYNANTON_JWT_SECRET}") String jwtSecret) {
        return new HttpRelixCanonicalEntityLookup(relixBaseUrl,
                new ServiceTokenMinter(jwtSecret), new ObjectMapper());
    }
}
