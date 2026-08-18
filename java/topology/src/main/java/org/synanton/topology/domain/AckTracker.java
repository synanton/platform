package org.synanton.topology.domain;

import org.synanton.topology.domain.model.PropagationId;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects consumer acks for HIGH_SECURITY 2-phase ACL propagation.
 */
public class AckTracker {

    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Integer>> acks = new ConcurrentHashMap<>();

    public void record(UUID outboxId, String consumer, int status) {
        acks.computeIfAbsent(outboxId, ignored -> new ConcurrentHashMap<>()).put(consumer, status);
    }

    public boolean allAcked(UUID outboxId, Iterable<String> consumers) {
        Map<String, Integer> received = acks.getOrDefault(outboxId, new ConcurrentHashMap<>());
        for (String consumer : consumers) {
            if (!Integer.valueOf(0).equals(received.get(consumer))) {
                return false;
            }
        }
        return true;
    }

    public boolean await(UUID outboxId, Iterable<String> consumers, Duration deadline) {
        long until = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < until) {
            if (allAcked(outboxId, consumers)) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return allAcked(outboxId, consumers);
    }

    public String state(UUID outboxId, Iterable<String> consumers) {
        return allAcked(outboxId, consumers) ? PropagationId.PROPAGATED : PropagationId.PENDING;
    }
}
