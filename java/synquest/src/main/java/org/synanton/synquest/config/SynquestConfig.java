package org.synanton.synquest.config;

import org.synanton.llm.HttpLlmClient;
import org.synanton.llm.LlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SynquestProperties.class)
public class SynquestConfig {

    @Bean
    public LlmClient llmClient(
            @Value("${llm-client.embed.base-url:http://vllm-embed:8001/v1}") String baseUrl,
            @Value("${llm-client.embed.max-retries:3}") int maxRetries) {
        return new HttpLlmClient(baseUrl, maxRetries);
    }
}
