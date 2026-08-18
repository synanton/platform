package org.synanton.planner.service;

import java.util.List;

public record IntentClassificationResult(
        String intent,
        double confidence,
        List<String> entityHints,
        List<String> relationHints,
        String llmModel,
        long llmLatencyMs
) {}
