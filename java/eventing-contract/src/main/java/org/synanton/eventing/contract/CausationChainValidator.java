package org.synanton.eventing.contract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Validates a complete, single-tenant causal message tree before it is used as contract evidence. */
public final class CausationChainValidator {

    private CausationChainValidator() {
    }

    /**
     * Requires one root with no cause; every other message must point to a present parent in the
     * same correlation and tenant scope. A complete flow may branch, but may not contain cycles.
     */
    public static void validateCompleteChain(Collection<? extends TenantScopedMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("a complete causal chain must not be empty");
        }

        Map<UUID, TenantScopedMessage> byId = new HashMap<>();
        List<TenantScopedMessage> roots = new ArrayList<>();
        for (TenantScopedMessage message : messages) {
            Objects.requireNonNull(message, "message");
            if (byId.putIfAbsent(message.messageId(), message) != null) {
                throw new IllegalArgumentException("message IDs must be unique within a causal chain");
            }
            if (message.causationId().isEmpty()) {
                roots.add(message);
            }
        }
        if (roots.size() != 1) {
            throw new IllegalArgumentException("a complete causal chain must have exactly one root");
        }

        TenantScopedMessage root = roots.getFirst();
        Map<UUID, List<UUID>> childrenByParent = new HashMap<>();
        for (TenantScopedMessage message : messages) {
            if (!root.correlationId().equals(message.correlationId())) {
                throw new IllegalArgumentException("all messages in a causal chain must share correlationId");
            }
            if (!root.tenantId().equals(message.tenantId())) {
                throw new IllegalArgumentException("causal messages must share tenant scope");
            }
            message.causationId().ifPresent(parentId -> {
                if (!byId.containsKey(parentId)) {
                    throw new IllegalArgumentException("causationId must reference a message in the chain");
                }
                if (message.messageId().equals(parentId)) {
                    throw new IllegalArgumentException("a message must not cause itself");
                }
                childrenByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>())
                        .add(message.messageId());
            });
        }

        rejectCycles(byId);

        Set<UUID> visited = new HashSet<>();
        ArrayDeque<UUID> pending = new ArrayDeque<>();
        pending.add(root.messageId());
        while (!pending.isEmpty()) {
            UUID current = pending.removeFirst();
            if (!visited.add(current)) {
                throw new IllegalArgumentException("causation graph must not contain cycles");
            }
            pending.addAll(childrenByParent.getOrDefault(current, List.of()));
        }
        if (visited.size() != byId.size()) {
            throw new IllegalArgumentException("every message must descend from the root");
        }
    }

    private static void rejectCycles(Map<UUID, TenantScopedMessage> messagesById) {
        Set<UUID> fullyExplored = new HashSet<>();
        for (UUID start : messagesById.keySet()) {
            Set<UUID> currentPath = new HashSet<>();
            UUID current = start;
            while (current != null && !fullyExplored.contains(current)) {
                if (!currentPath.add(current)) {
                    throw new IllegalArgumentException("causation graph must not contain cycles");
                }
                current = messagesById.get(current).causationId().orElse(null);
            }
            fullyExplored.addAll(currentPath);
        }
    }
}
