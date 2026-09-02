package org.synanton.annotations.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.annotations.domain.model.AnnotationDefinition;
import org.synanton.annotations.domain.model.AnnotationDefinitionVersion;
import org.synanton.annotations.domain.repository.AnnotationDefinitionRepository;
import org.synanton.annotations.domain.repository.AnnotationDefinitionVersionRepository;
import org.synanton.common.error.NotFoundException;
import org.synanton.common.error.ValidationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnotationDefinitionServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryDefinitionRepository definitions = new InMemoryDefinitionRepository();
    private final InMemoryVersionRepository versions = new InMemoryVersionRepository();
    private final AnnotationDefinitionService service = new AnnotationDefinitionService(definitions, versions, clock);

    @BeforeEach
    void createDefinition() {
        service.createDefinition("payment-detection", "billing", "payment", "TAG");
    }

    @Test
    void shouldRejectUnknownAnnotationType() {
        assertThatThrownBy(() -> service.createDefinition("bad-type", "ns", "n", "NOT_A_TYPE"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void shouldRejectNonSlugDefinitionId() {
        assertThatThrownBy(() -> service.createDefinition("Not A Slug", "ns", "n", "TAG"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void shouldCreateVersionInDraftStatus() {
        AnnotationDefinitionVersion v = service.createVersion(
                "payment-detection", 4, List.of("invoice_number"), "payment-rule-engine", "4.2",
                "annotation", "payment");

        assertThat(v.status()).isEqualTo(AnnotationDefinitionVersion.DRAFT);
        assertThat(v.isMutable()).isTrue();
    }

    @Test
    void shouldAllowEditingADraftVersion() {
        service.createVersion("payment-detection", 4, List.of("a"), "p", "1", "annotation", "payment");

        AnnotationDefinitionVersion updated = service.updateVersion(
                "payment-detection", 4, List.of("a", "b"), "p", "2", "annotation", "payment");

        assertThat(updated.inputs()).containsExactly("a", "b");
        assertThat(updated.producerVersion()).isEqualTo("2");
    }

    @Test
    void shouldPublishADraftVersion() {
        service.createVersion("payment-detection", 4, List.of("a"), "p", "1", "annotation", "payment");

        AnnotationDefinitionVersion published = service.publish("payment-detection", 4);

        assertThat(published.status()).isEqualTo(AnnotationDefinitionVersion.PUBLISHED);
        assertThat(published.publishedAt()).isNotNull();
    }

    @Test
    void shouldRejectSecondPublishOfSameVersion() {
        service.createVersion("payment-detection", 4, List.of("a"), "p", "1", "annotation", "payment");
        service.publish("payment-detection", 4);

        assertThatThrownBy(() -> service.publish("payment-detection", 4))
                .isInstanceOf(AlreadyPublishedException.class);
    }

    @Test
    void shouldRejectEditingAPublishedVersion() {
        service.createVersion("payment-detection", 4, List.of("a"), "p", "1", "annotation", "payment");
        service.publish("payment-detection", 4);

        assertThatThrownBy(() -> service.updateVersion(
                "payment-detection", 4, List.of("changed"), "p", "1", "annotation", "payment"))
                .isInstanceOf(AlreadyPublishedException.class);
    }

    @Test
    void shouldFailToCreateVersionForUnknownDefinition() {
        assertThatThrownBy(() -> service.createVersion(
                "does-not-exist", 1, List.of(), "p", "1", "annotation", "x"))
                .isInstanceOf(NotFoundException.class);
    }

    private static class InMemoryDefinitionRepository implements AnnotationDefinitionRepository {
        private final Map<String, AnnotationDefinition> stored = new HashMap<>();

        @Override
        public AnnotationDefinition insert(AnnotationDefinition definition) {
            stored.put(definition.definitionId(), definition);
            return definition;
        }

        @Override
        public Optional<AnnotationDefinition> findById(String definitionId) {
            return Optional.ofNullable(stored.get(definitionId));
        }
    }

    private static class InMemoryVersionRepository implements AnnotationDefinitionVersionRepository {
        private final Map<String, AnnotationDefinitionVersion> stored = new HashMap<>();

        private static String key(String definitionId, int version) {
            return definitionId + "@" + version;
        }

        @Override
        public AnnotationDefinitionVersion insert(AnnotationDefinitionVersion version) {
            stored.put(key(version.definitionId(), version.version()), version);
            return version;
        }

        @Override
        public Optional<AnnotationDefinitionVersion> find(String definitionId, int version) {
            return Optional.ofNullable(stored.get(key(definitionId, version)));
        }

        @Override
        public List<AnnotationDefinitionVersion> findByDefinitionId(String definitionId) {
            return stored.values().stream().filter(v -> v.definitionId().equals(definitionId)).toList();
        }

        @Override
        public void updateContent(AnnotationDefinitionVersion version) {
            stored.put(key(version.definitionId(), version.version()), version);
        }

        @Override
        public void updateStatus(String definitionId, int version, String status, Instant publishedAt) {
            AnnotationDefinitionVersion current = stored.get(key(definitionId, version));
            stored.put(key(definitionId, version), new AnnotationDefinitionVersion(
                    current.definitionId(), current.version(), current.inputs(), current.producer(),
                    current.producerVersion(), current.outputType(), current.outputName(), status,
                    publishedAt, current.createdAt()));
        }

        @Override
        public List<AnnotationDefinitionVersion> findAllPublished() {
            return stored.values().stream()
                    .filter(v -> AnnotationDefinitionVersion.PUBLISHED.equals(v.status()))
                    .toList();
        }
    }
}
