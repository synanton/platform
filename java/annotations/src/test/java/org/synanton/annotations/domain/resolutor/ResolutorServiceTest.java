package org.synanton.annotations.domain.resolutor;

import org.junit.jupiter.api.Test;
import org.synanton.annotations.domain.model.DependencyEdge;
import org.synanton.annotations.domain.repository.DependencyEdgeRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolutorServiceTest {

    private final InMemoryEdgeRepository edges = new InMemoryEdgeRepository();
    private final InMemoryInstanceStore instanceStore = new InMemoryInstanceStore();
    private final ResolutorService resolutor = new ResolutorService(edges, instanceStore);

    @Test
    void shouldOnlyIncludeTargetsOfTheChangedDefinitionWhenNothingDependsOnIt() {
        instanceStore.seed("payment", List.of(new AnnotationInstanceStore.TargetRef("chunk", "c1")));
        instanceStore.seed("unrelated", List.of(new AnnotationInstanceStore.TargetRef("chunk", "c99")));

        RecalculationPlan plan = resolutor.resolve(
                new ChangeEvent(ChangeType.ANNOTATION_DEFINITION_VERSION_PUBLISHED, "payment", 3, 4), "demo");

        assertThat(plan.workItems()).hasSize(1);
        RecalculationWorkItem item = plan.workItems().getFirst();
        assertThat(item.definitionId()).isEqualTo("payment");
        assertThat(item.targetId()).isEqualTo("c1");
        assertThat(item.fromVersion()).isEqualTo(3);
        assertThat(item.toVersion()).isEqualTo(4);
        // "unrelated" must never appear - Resolutor is not allowed to touch the whole corpus.
        assertThat(plan.workItems()).noneMatch(w -> w.definitionId().equals("unrelated"));
    }

    @Test
    void shouldIncludeTransitiveDownstreamDependentsWithoutAVersionMove() {
        // billing-issue depends on payment; escalation-required depends on billing-issue.
        edges.add("billing-issue", 1, "payment", 4);
        edges.add("escalation-required", 1, "billing-issue", 1);
        instanceStore.seed("payment", List.of(new AnnotationInstanceStore.TargetRef("chunk", "c1")));
        instanceStore.seed("billing-issue", List.of(new AnnotationInstanceStore.TargetRef("chunk", "c1")));
        instanceStore.seed("escalation-required", List.of(new AnnotationInstanceStore.TargetRef("chunk", "c1")));

        RecalculationPlan plan = resolutor.resolve(
                new ChangeEvent(ChangeType.ANNOTATION_DEFINITION_VERSION_PUBLISHED, "payment", 3, 4), "demo");

        List<String> affectedDefinitions = plan.workItems().stream().map(RecalculationWorkItem::definitionId).distinct().toList();
        assertThat(affectedDefinitions).containsExactlyInAnyOrder("payment", "billing-issue", "escalation-required");

        RecalculationWorkItem downstream = plan.workItems().stream()
                .filter(w -> w.definitionId().equals("billing-issue")).findFirst().orElseThrow();
        assertThat(downstream.fromVersion()).isNull();
        assertThat(downstream.toVersion()).isNull();
    }

    @Test
    void shouldBeDeterministicAcrossRepeatedCalls() {
        edges.add("billing-issue", 1, "payment", 4);
        edges.add("duplicate-charge", 2, "payment", 4);
        instanceStore.seed("payment", List.of(
                new AnnotationInstanceStore.TargetRef("chunk", "c3"), new AnnotationInstanceStore.TargetRef("chunk", "c1")));
        instanceStore.seed("billing-issue", List.of(new AnnotationInstanceStore.TargetRef("chunk", "c2")));
        instanceStore.seed("duplicate-charge", List.of(new AnnotationInstanceStore.TargetRef("chunk", "c4")));
        ChangeEvent event = new ChangeEvent(ChangeType.ANNOTATION_DEFINITION_VERSION_PUBLISHED, "payment", 3, 4);

        RecalculationPlan first = resolutor.resolve(event, "demo");
        RecalculationPlan second = resolutor.resolve(event, "demo");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void shouldRejectUnwiredChangeTypes() {
        assertThatThrownBy(() -> resolutor.resolve(
                new ChangeEvent(ChangeType.SOURCE_CHANGED, "payment", null, null), "demo"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static class InMemoryEdgeRepository implements DependencyEdgeRepository {
        private final List<DependencyEdge> stored = new ArrayList<>();

        void add(String fromDef, int fromVersion, String toDef, int toVersion) {
            stored.add(new DependencyEdge(fromDef, fromVersion, toDef, toVersion, Instant.now()));
        }

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
            return stored.stream().filter(e -> e.fromDefinitionId().equals(definitionId) && e.fromVersion() == version).toList();
        }
    }

    private static class InMemoryInstanceStore implements AnnotationInstanceStore {
        private final Map<String, List<TargetRef>> byDefinition = new HashMap<>();

        void seed(String definitionId, List<TargetRef> targets) {
            byDefinition.put(definitionId, targets);
        }

        @Override
        public List<TargetRef> findTargets(String tenantId, String definitionId) {
            return byDefinition.getOrDefault(definitionId, List.of());
        }

        @Override
        public void invalidate(String tenantId, String targetType, String targetId, String definitionId, int version) {
            // not exercised by ResolutorServiceTest
        }
    }
}
