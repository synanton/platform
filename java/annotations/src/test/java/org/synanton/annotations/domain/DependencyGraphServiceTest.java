package org.synanton.annotations.domain;

import org.junit.jupiter.api.Test;
import org.synanton.annotations.domain.model.AnnotationDefinitionVersion;
import org.synanton.annotations.domain.model.DependencyEdge;
import org.synanton.annotations.domain.repository.AnnotationDefinitionVersionRepository;
import org.synanton.annotations.domain.repository.DependencyEdgeRepository;
import org.synanton.common.error.NotFoundException;
import org.synanton.common.error.ValidationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DependencyGraphServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryEdgeRepository edges = new InMemoryEdgeRepository();
    private final AlwaysPresentVersionRepository versions = new AlwaysPresentVersionRepository();
    private final DependencyGraphService service = new DependencyGraphService(edges, versions, clock);

    @Test
    void shouldRegisterASimpleDependency() {
        DependencyEdge edge = service.addDependency("billing-issue", 1, "payment", 3);

        assertThat(edge.fromDefinitionId()).isEqualTo("billing-issue");
        assertThat(edge.toDefinitionId()).isEqualTo("payment");
        assertThat(edges.findAll()).hasSize(1);
    }

    @Test
    void shouldRejectSelfDependency() {
        assertThatThrownBy(() -> service.addDependency("payment", 3, "payment", 3))
                .isInstanceOf(ValidationException.class);
        assertThat(edges.findAll()).isEmpty();
    }

    @Test
    void shouldRejectDirectCycle() {
        service.addDependency("a", 1, "b", 1);

        assertThatThrownBy(() -> service.addDependency("b", 1, "a", 1))
                .isInstanceOf(CyclicDependencyException.class);
        assertThat(edges.findAll()).hasSize(1);
    }

    @Test
    void shouldRejectTransitiveCycle() {
        // A -> B -> C, then C -> A would close the loop.
        service.addDependency("a", 1, "b", 1);
        service.addDependency("b", 1, "c", 1);

        assertThatThrownBy(() -> service.addDependency("c", 1, "a", 1))
                .isInstanceOf(CyclicDependencyException.class);
        assertThat(edges.findAll()).hasSize(2);
    }

    @Test
    void shouldAllowDiamondDependency() {
        // A depends on B and C; both B and C depend on D. Not a cycle.
        service.addDependency("a", 1, "b", 1);
        service.addDependency("a", 1, "c", 1);
        service.addDependency("b", 1, "d", 1);
        service.addDependency("c", 1, "d", 1);

        assertThat(edges.findAll()).hasSize(4);
    }

    @Test
    void shouldFailWhenDefinitionVersionUnknown() {
        versions.presentOverride = false;
        assertThatThrownBy(() -> service.addDependency("a", 1, "b", 1))
                .isInstanceOf(NotFoundException.class);
    }

    private static class InMemoryEdgeRepository implements DependencyEdgeRepository {
        private final List<DependencyEdge> stored = new ArrayList<>();

        @Override
        public DependencyEdge insert(DependencyEdge edge) {
            stored.add(edge);
            return edge;
        }

        @Override
        public List<DependencyEdge> findAll() {
            return List.copyOf(stored);
        }

        @Override
        public List<DependencyEdge> findByFrom(String definitionId, int version) {
            return stored.stream()
                    .filter(e -> e.fromDefinitionId().equals(definitionId) && e.fromVersion() == version)
                    .toList();
        }
    }

    /** Fake that treats every requested definition version as existing, unless {@code presentOverride} is flipped. */
    private static class AlwaysPresentVersionRepository implements AnnotationDefinitionVersionRepository {
        boolean presentOverride = true;

        @Override
        public AnnotationDefinitionVersion insert(AnnotationDefinitionVersion version) {
            return version;
        }

        @Override
        public Optional<AnnotationDefinitionVersion> find(String definitionId, int version) {
            if (!presentOverride) {
                return Optional.empty();
            }
            return Optional.of(new AnnotationDefinitionVersion(
                    definitionId, version, List.of(), "producer", "1.0", "annotation", "value",
                    AnnotationDefinitionVersion.PUBLISHED, Instant.now(), Instant.now()));
        }

        @Override
        public List<AnnotationDefinitionVersion> findByDefinitionId(String definitionId) {
            return List.of();
        }

        @Override
        public void updateContent(AnnotationDefinitionVersion version) {}

        @Override
        public void updateStatus(String definitionId, int version, String status, Instant publishedAt) {}

        @Override
        public List<AnnotationDefinitionVersion> findAllPublished() {
            return List.of();
        }
    }
}
