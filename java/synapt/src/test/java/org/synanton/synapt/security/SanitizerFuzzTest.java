package org.synanton.synapt.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.synanton.common.security.sanitizer.HtmlSanitizerPolicyFactory;
import org.synanton.common.security.sanitizer.SanitizerProperties;
import org.synanton.common.security.sanitizer.SanitizerTestKit;
import org.synanton.common.security.sanitizer.SanitizingModule;
import org.synanton.synapt.domain.SearchRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizerFuzzTest {

    @Test
    void shouldSanitiseTenThousandOwaspVariants() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new SanitizingModule(
                new HtmlSanitizerPolicyFactory(SanitizerProperties.platformDefault()),
                new SimpleMeterRegistry()
        ));
        for (String payload : SanitizerTestKit.fuzzVariants(10_000)) {
            String json = mapper.writeValueAsString(new SearchRequest(payload, 10, null));
            SearchRequest parsed = mapper.readValue(json, SearchRequest.class);
            String query = parsed.query() == null ? "" : parsed.query().toLowerCase();
            assertThat(query).doesNotContain("<script");
        }
    }
}
