package org.synanton.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Deficit round-robin weighted-fair scheduler across tenants.
 */
public class WeightedFairScheduler {

    public record WorkItem(String tenantId, String json, String priority, String workClass, int weight) {}

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<WorkItem>> queues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> deficit = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> inflight = new ConcurrentHashMap<>();
    private volatile boolean degradedMode;

    public void enqueue(WorkItem item) {
        if (degradedMode && "RECRAWL_BACKGROUND".equals(item.priority())) {
            return;
        }
        queues.computeIfAbsent(item.tenantId(), ignored -> new ConcurrentLinkedQueue<>()).add(item);
    }

    public void setDegradedMode(boolean degradedMode) {
        this.degradedMode = degradedMode;
    }

    public WorkItem poll(Map<String, Integer> maxConcurrentByTenant) {
        List<String> tenants = new ArrayList<>(queues.keySet());
        tenants.sort(Comparator.naturalOrder());
        WorkItem interactive = takePriority(tenants, maxConcurrentByTenant, "INTERACTIVE");
        if (interactive != null) {
            return interactive;
        }
        return takePriority(tenants, maxConcurrentByTenant, null);
    }

    private WorkItem takePriority(
            List<String> tenants,
            Map<String, Integer> maxConcurrentByTenant,
            String requiredPriority
    ) {
        for (String tenant : tenants) {
            int cap = maxConcurrentByTenant.getOrDefault(tenant, Integer.MAX_VALUE);
            if (inflight.getOrDefault(tenant, 0) >= cap) {
                continue;
            }
            ConcurrentLinkedQueue<WorkItem> queue = queues.get(tenant);
            if (queue == null || queue.isEmpty()) {
                continue;
            }
            WorkItem head = queue.peek();
            int weight = head == null ? 100 : Math.max(1, head.weight());
            deficit.merge(tenant, weight, Integer::sum);
            WorkItem item = requiredPriority == null
                    ? queue.poll()
                    : takeNamedPriority(queue, requiredPriority);
            if (item != null) {
                deficit.merge(tenant, -1, Integer::sum);
                inflight.merge(tenant, 1, Integer::sum);
                return item;
            }
        }
        return null;
    }

    public void complete(String tenantId) {
        inflight.compute(tenantId, (key, value) -> value == null || value <= 1 ? 0 : value - 1);
    }

    public int queued(String tenantId) {
        ConcurrentLinkedQueue<WorkItem> queue = queues.get(tenantId);
        return queue == null ? 0 : queue.size();
    }

    public static String classify(String workClass) {
        if ("USER_TRIGGERED".equals(workClass)) {
            return "INTERACTIVE";
        }
        if ("RECRAWL_AFTER_RESTORATION".equals(workClass)) {
            return "RECRAWL_BACKGROUND";
        }
        return "BACKFILL";
    }

    private static WorkItem takeNamedPriority(ConcurrentLinkedQueue<WorkItem> queue, String priority) {
        WorkItem match = queue.stream().filter(item -> priority.equals(item.priority())).findFirst().orElse(null);
        if (match != null && queue.remove(match)) {
            return match;
        }
        return null;
    }
}
