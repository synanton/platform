package org.synanton.annotations.domain.resolutor;

import org.synanton.annotations.domain.model.DefinitionVersionKey;
import org.synanton.annotations.domain.model.DependencyEdge;
import org.synanton.annotations.domain.repository.DependencyEdgeRepository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolutor: deterministic impact analysis (design §49). Given a change, walks the
 * dependency DAG (AAP-1) to find every definition transitively affected, then looks up
 * that definition's currently-annotated targets to build a concrete recalculation plan.
 *
 * <p>Determinism (design §49: "deterministic for a given dependency graph and change
 * set") holds because every intermediate set is fully sorted before the final work-item
 * list is built - iteration order of the underlying repositories never leaks through.
 */
public class ResolutorService {

    private final DependencyEdgeRepository edges;
    private final AnnotationInstanceStore instanceStore;

    public ResolutorService(DependencyEdgeRepository edges, AnnotationInstanceStore instanceStore) {
        this.edges = edges;
        this.instanceStore = instanceStore;
    }

    public RecalculationPlan resolve(ChangeEvent event, String tenantId) {
        if (event.changeType() != ChangeType.ANNOTATION_DEFINITION_VERSION_PUBLISHED) {
            throw new UnsupportedOperationException(
                    "Resolutor does not yet consume " + event.changeType() + " events - no upstream producer is "
                            + "wired for it yet (see docs/implementation/annotations-analytics-plane/02-recalculation.md work item 1)");
        }

        DefinitionVersionKey changedNode = new DefinitionVersionKey(event.definitionId(), event.toVersion());
        List<DependencyEdge> allEdges = edges.findAll();

        List<String> affectedDefinitionIds = new ArrayList<>();
        affectedDefinitionIds.add(event.definitionId());
        reverseReachable(allEdges, changedNode).stream()
                .map(DefinitionVersionKey::definitionId)
                .distinct()
                .sorted()
                .forEach(affectedDefinitionIds::add);

        List<RecalculationWorkItem> workItems = new ArrayList<>();
        for (String definitionId : affectedDefinitionIds) {
            boolean isChangedDefinition = definitionId.equals(event.definitionId());
            List<AnnotationInstanceStore.TargetRef> targets = instanceStore.findTargets(tenantId, definitionId).stream()
                    .sorted(Comparator.comparing(AnnotationInstanceStore.TargetRef::targetType)
                            .thenComparing(AnnotationInstanceStore.TargetRef::targetId))
                    .toList();
            for (AnnotationInstanceStore.TargetRef target : targets) {
                workItems.add(new RecalculationWorkItem(
                        target.targetType(), target.targetId(), definitionId,
                        isChangedDefinition ? event.fromVersion() : null,
                        isChangedDefinition ? event.toVersion() : null));
            }
        }

        return new RecalculationPlan(List.copyOf(workItems), EnumSet.allOf(Projection.class));
    }

    /**
     * Nodes that depend, directly or transitively, on {@code target} - i.e. every node
     * with a path {@code from -> ... -> target} in the DAG. Same backward-traversal shape
     * as AAP-1's {@code DependencyGraphService} cycle check, for the opposite question.
     */
    private static Set<DefinitionVersionKey> reverseReachable(List<DependencyEdge> allEdges, DefinitionVersionKey target) {
        Deque<DefinitionVersionKey> stack = new ArrayDeque<>();
        stack.push(target);
        Set<DefinitionVersionKey> visited = new HashSet<>();
        Set<DefinitionVersionKey> result = new LinkedHashSet<>();

        while (!stack.isEmpty()) {
            DefinitionVersionKey current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (DependencyEdge edge : allEdges) {
                if (edge.to().equals(current)) {
                    result.add(edge.from());
                    stack.push(edge.from());
                }
            }
        }
        return result;
    }
}
