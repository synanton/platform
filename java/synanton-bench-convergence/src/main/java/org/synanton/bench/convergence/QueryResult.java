package org.synanton.bench.convergence;

import java.util.List;

/**
 * One query leg result in Q3 JSON format. {@code mode} is
 * lexical/vector/hybrid; {@code filter} is none/eligibility/metadata.
 */
public record QueryResult(
        String queryId,
        String mode,
        String filter,
        String selectivity,
        List<TopKEntry> topK,
        List<String> eligibleSet,
        double timingMs) {}
