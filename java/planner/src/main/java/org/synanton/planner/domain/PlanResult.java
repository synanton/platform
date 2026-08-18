package org.synanton.planner.domain;

import java.util.Map;

public record PlanResult(
        String templateId,
        String intent,
        String query,
        String tenant,
        Map<String, Object> slots,
        double confidence,
        PlanTrace trace
) {}
