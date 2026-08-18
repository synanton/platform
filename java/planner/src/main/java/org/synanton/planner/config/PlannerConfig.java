package org.synanton.planner.config;

import org.synanton.llm.HttpLlmClient;
import org.synanton.llm.LlmClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PlannerProperties.class)
public class PlannerConfig {

    @Bean
    public LlmClient llmClient(PlannerProperties props) {
        return new HttpLlmClient(props.llm().baseUrl(), props.llm().maxRetries());
    }
}
