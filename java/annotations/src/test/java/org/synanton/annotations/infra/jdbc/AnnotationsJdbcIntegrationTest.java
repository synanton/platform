package org.synanton.annotations.infra.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.synanton.annotations.domain.model.AnnotationDefinition;
import org.synanton.annotations.domain.model.AnnotationDefinitionVersion;
import org.synanton.annotations.domain.model.DependencyEdge;
import org.synanton.annotations.domain.model.ProcessingRun;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import({
        JdbcAnnotationDefinitionRepository.class,
        JdbcAnnotationDefinitionVersionRepository.class,
        JdbcDependencyEdgeRepository.class,
        JdbcProcessingRunRepository.class,
        AnnotationsJdbcIntegrationTest.TestConfig.class
})
@Sql("/db/schema-test.sql")
class AnnotationsJdbcIntegrationTest {

    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired JdbcAnnotationDefinitionRepository definitions;
    @Autowired JdbcAnnotationDefinitionVersionRepository versions;
    @Autowired JdbcDependencyEdgeRepository edges;
    @Autowired JdbcProcessingRunRepository processingRuns;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM annotations.dependency_edges");
        jdbc.update("DELETE FROM annotations.processing_runs");
        jdbc.update("DELETE FROM annotations.annotation_definition_versions");
        jdbc.update("DELETE FROM annotations.annotation_definitions");
    }

    @Test
    void shouldInsertAndFindDefinition() {
        definitions.insert(new AnnotationDefinition("payment-detection", "billing", "payment", "TAG", Instant.now()));

        var found = definitions.findById("payment-detection");

        assertThat(found).isPresent();
        assertThat(found.get().namespace()).isEqualTo("billing");
    }

    @Test
    void shouldRoundTripVersionInputsJson() {
        definitions.insert(new AnnotationDefinition("payment-detection", "billing", "payment", "TAG", Instant.now()));
        versions.insert(new AnnotationDefinitionVersion(
                "payment-detection", 4, List.of("invoice_number", "payment_reference"),
                "payment-rule-engine", "4.2", "annotation", "payment",
                AnnotationDefinitionVersion.DRAFT, null, Instant.now()));

        var found = versions.find("payment-detection", 4);

        assertThat(found).isPresent();
        assertThat(found.get().inputs()).containsExactly("invoice_number", "payment_reference");
    }

    @Test
    void shouldPublishAndListOnlyPublishedVersions() {
        definitions.insert(new AnnotationDefinition("payment-detection", "billing", "payment", "TAG", Instant.now()));
        versions.insert(new AnnotationDefinitionVersion(
                "payment-detection", 3, List.of(), "p", "1", "annotation", "payment",
                AnnotationDefinitionVersion.DRAFT, null, Instant.now()));
        versions.insert(new AnnotationDefinitionVersion(
                "payment-detection", 4, List.of(), "p", "2", "annotation", "payment",
                AnnotationDefinitionVersion.DRAFT, null, Instant.now()));

        versions.updateStatus("payment-detection", 4, AnnotationDefinitionVersion.PUBLISHED, Instant.now());

        List<AnnotationDefinitionVersion> published = versions.findAllPublished();
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().version()).isEqualTo(4);
    }

    @Test
    void shouldPersistDependencyEdgesAndQueryByFrom() {
        definitions.insert(new AnnotationDefinition("billing-issue", "billing", "billing-issue", "TAG", Instant.now()));
        definitions.insert(new AnnotationDefinition("payment", "billing", "payment", "TAG", Instant.now()));
        versions.insert(new AnnotationDefinitionVersion(
                "billing-issue", 1, List.of(), "p", "1", "annotation", "billing-issue",
                AnnotationDefinitionVersion.PUBLISHED, Instant.now(), Instant.now()));
        versions.insert(new AnnotationDefinitionVersion(
                "payment", 3, List.of(), "p", "1", "annotation", "payment",
                AnnotationDefinitionVersion.PUBLISHED, Instant.now(), Instant.now()));

        edges.insert(new DependencyEdge("billing-issue", 1, "payment", 3, Instant.now()));

        assertThat(edges.findAll()).hasSize(1);
        assertThat(edges.findByFrom("billing-issue", 1)).hasSize(1);
        assertThat(edges.findByFrom("payment", 3)).isEmpty();
    }

    @Test
    void shouldStartAndCompleteAProcessingRun() {
        ProcessingRun started = processingRuns.insert(new ProcessingRun(
                UUID.randomUUID(), "annotation-engine", "4.2", "tenant-17", "payment-detection", 4,
                "tenant-17", Instant.now(), null, ProcessingRun.RUNNING, null, null));

        processingRuns.complete(started.processingRunId(), ProcessingRun.SUCCEEDED, Instant.now(), null, "{\"cpuMs\":120}");

        var found = processingRuns.findById(started.processingRunId());
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(ProcessingRun.SUCCEEDED);
        assertThat(found.get().endedAt()).isNotNull();
        assertThat(found.get().resourceConsumptionJson()).contains("cpuMs");
    }
}
