package org.synanton.planner.service;

import java.util.Map;

public class ContextBudgetAllocator {

    public record Budget(int totalTokens, Map<String, Integer> allocation) {}

    public Budget allocate(String intent, int maxContextTokens) {
        int total = maxContextTokens <= 0 ? 32000 : maxContextTokens;
        return switch (intent) {
            case "SYNTHESIS" -> new Budget(total, Map.of(
                    "chunks", (int) (total * 0.55),
                    "graph", (int) (total * 0.15),
                    "history", (int) (total * 0.15),
                    "system", (int) (total * 0.15)));
            case "LOOKUP" -> new Budget(Math.min(total, 8000), Map.of(
                    "chunks", 5000, "graph", 1000, "history", 1000, "system", 1000));
            default -> new Budget(total, Map.of(
                    "chunks", (int) (total * 0.4),
                    "graph", (int) (total * 0.3),
                    "history", (int) (total * 0.15),
                    "system", (int) (total * 0.15)));
        };
    }
}
