package org.synanton.controlplane.service;

import java.util.ArrayList;
import java.util.List;

public class AnomalyDetector {

    public record Recommendation(String tenantId, String reason, double score) {}

    public List<Recommendation> detect(List<Double> latenciesMs, String tenantId, double thresholdMs) {
        List<Recommendation> open = new ArrayList<>();
        double mean = latenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (mean > thresholdMs) {
            open.add(new Recommendation(tenantId, "slow_query_mean", mean));
        }
        return open;
    }
}
