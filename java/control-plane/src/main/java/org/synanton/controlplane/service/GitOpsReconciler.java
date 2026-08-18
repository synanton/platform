package org.synanton.controlplane.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class GitOpsReconciler {

    public record TenantSpec(String tenantId, String tier, Map<String, Object> policy) {}

    public record Diff(String tenantId, boolean changed) {}

    private final CopyOnWriteArrayList<TenantSpec> desired = new CopyOnWriteArrayList<>();

    public void load(List<TenantSpec> specs) {
        desired.clear();
        desired.addAll(specs);
    }

    public List<Diff> reconcile(Map<String, String> currentTiers) {
        List<Diff> diffs = new ArrayList<>();
        for (TenantSpec spec : desired) {
            boolean changed = !spec.tier().equals(currentTiers.get(spec.tenantId()));
            diffs.add(new Diff(spec.tenantId(), changed));
        }
        return diffs;
    }
}
