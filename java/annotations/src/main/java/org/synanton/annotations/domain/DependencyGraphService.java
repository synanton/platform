package org.synanton.annotations.domain;

import org.synanton.annotations.domain.model.DefinitionVersionKey;
import org.synanton.annotations.domain.model.DependencyEdge;
import org.synanton.annotations.domain.repository.AnnotationDefinitionVersionRepository;
import org.synanton.annotations.domain.repository.DependencyEdgeRepository;
import org.synanton.common.error.NotFoundException;
import org.synanton.common.error.ValidationException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolutor's prerequisite: maintains the annotation dependency DAG (design §10-§11)
 * and deterministically rejects any edge that would introduce a cycle
 * (design §10: "Circular dependencies are rejected", e.g. {@code A -> B -> C -> A}).
 *
 * <p>Determinism is load-bearing here - design §49 requires Resolutor's impact
 * analysis to be "deterministic for a given dependency graph and change set", which
 * only holds if the graph itself can never contain a cycle.
 */
public class DependencyGraphService {

    private final DependencyEdgeRepository edges;
    private final AnnotationDefinitionVersionRepository versions;
    private final Clock clock;

    public DependencyGraphService(
            DependencyEdgeRepository edges,
            AnnotationDefinitionVersionRepository versions,
            Clock clock
    ) {
        this.edges = edges;
        this.versions = versions;
        this.clock = clock;
    }

    /**
     * Registers a dependency of {@code from} on {@code to} ({@code to} must be computed
     * first). Rejects self-loops and any edge that would create a cycle, without
     * persisting anything in that case.
     */
    public DependencyEdge addDependency(String fromDefinitionId, int fromVersion, String toDefinitionId, int toVersion) {
        DefinitionVersionKey from = new DefinitionVersionKey(fromDefinitionId, fromVersion);
        DefinitionVersionKey to = new DefinitionVersionKey(toDefinitionId, toVersion);

        if (from.equals(to)) {
            throw new ValidationException("A definition version cannot depend on itself: " + from);
        }
        versions.find(fromDefinitionId, fromVersion)
                .orElseThrow(() -> new NotFoundException("Unknown definition version: " + from));
        versions.find(toDefinitionId, toVersion)
                .orElseThrow(() -> new NotFoundException("Unknown definition version: " + to));

        List<DependencyEdge> existing = edges.findAll();
        if (canReach(existing, to, from)) {
            throw new CyclicDependencyException(
                    "Adding dependency " + from + " -> " + to + " would create a cycle");
        }

        return edges.insert(new DependencyEdge(fromDefinitionId, fromVersion, toDefinitionId, toVersion, Instant.now(clock)));
    }

    /**
     * True if {@code target} is reachable from {@code start} by following existing
     * edges forward (from -> to). Used to detect that adding {@code from -> to} would
     * close a cycle: that happens exactly when {@code to} can already reach {@code from}.
     */
    private boolean canReach(List<DependencyEdge> existingEdges, DefinitionVersionKey start, DefinitionVersionKey target) {
        Deque<DefinitionVersionKey> stack = new ArrayDeque<>();
        stack.push(start);
        Set<DefinitionVersionKey> visited = new HashSet<>();

        while (!stack.isEmpty()) {
            DefinitionVersionKey current = stack.pop();
            if (current.equals(target)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            for (DependencyEdge edge : existingEdges) {
                if (edge.from().equals(current)) {
                    stack.push(edge.to());
                }
            }
        }
        return false;
    }
}
