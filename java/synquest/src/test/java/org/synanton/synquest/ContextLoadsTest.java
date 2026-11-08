package org.synanton.synquest;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Component-scan smoke test: loads the full application context with the
 * Cassandra session mocked, so duplicate/missing beans (e.g. two
 * SearchIndexAdapter implementations) fail in CI in milliseconds instead of at
 * a live deploy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContextLoadsTest {

    @MockBean
    CqlSession cqlSession;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path ontology = Path.of("../../demo-data/eval/retrieval-benchmark/ontology-v2.jsonl")
                .toAbsolutePath().normalize();
        assertThat(Files.exists(ontology)).isTrue();
        Path index = Files.createTempDirectory("synquest-context-test");
        registry.add("ontology.v2.path", ontology::toString);
        registry.add("ontology.v2.sha256", () -> "00c7e8fb6e1385ffe97710c26523a720dbe57cd922653bf1d099e7dec051a389");
        registry.add("SYNANTON_JWT_SECRET", () -> "synquest-context-test-secret-at-least-32-bytes");
        registry.add("synquest.seed.demo-tenant", () -> "");
        registry.add("synquest.projection.enabled", () -> "false");
        registry.add("synquest.index.path", index::toString);
    }

    @Test
    void contextLoadsWithSingleEngineAdapters(ApplicationContext context) {
        assertThat(context).isNotNull();
        assertThat(context.getBeansOfType(
                org.synanton.synquest.search.SearchIndexAdapter.class)).hasSize(1);
        assertThat(context.getBean(org.synanton.synquest.service.SearchService.class)).isNotNull();
    }
}
