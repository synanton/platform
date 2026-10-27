package org.synanton.synquest.demo;

import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.synquest.service.KnowledgeSearchBackend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class DemoSearchConfig {

    @Bean
    DemoSearchService demoSearchService(
            KnowledgeSearchBackend backend,
            IngestionCacheClient cacheClient,
            @Value("${synquest.demo.dense-cache:demo-data/eval/retrieval-benchmark/dense-embeddings-v2.json}") String cache,
            @Value("${synquest.demo.queries:demo-data/eval/retrieval-benchmark/queries-v2.rb-fixed-g5.jsonl}") String queries) {
        return new DemoSearchService(backend, cacheClient, Path.of(cache), Path.of(queries));
    }
}
