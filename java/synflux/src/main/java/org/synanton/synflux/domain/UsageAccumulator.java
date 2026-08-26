package org.synanton.synflux.domain;

import java.util.ArrayList;
import java.util.List;

public final class UsageAccumulator {

    private final List<StageUsage> stages = new ArrayList<>();

    public void record(StageUsage stage) {
        stages.add(stage);
    }

    public List<StageUsage> stages() {
        return List.copyOf(stages);
    }

    public ResourceUsage snapshot() {
        return ResourceUsage.fromStages(stages);
    }
}
