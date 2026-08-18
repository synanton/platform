package org.synanton.planner.service;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

@Component
public class IntentClassifier {
    // T1: graph traversal / relationship queries
    private static final Pattern T1 = Pattern.compile(
        "(?i)\\b(related|connected|linked|neighbors?|adjacent|hop|path|between|relationship)\\b");
    // T2: entity-centric lookup
    private static final Pattern T2 = Pattern.compile(
        "(?i)\\b(who is|what is|tell me about|describe|profile of|overview of)\\b");
    // T3: document search / retrieval
    private static final Pattern T3 = Pattern.compile(
        "(?i)\\b(find|search|show|list|retrieve|documents?|files?|reports?)\\b");
    // T4: aggregation / summary
    private static final Pattern T4 = Pattern.compile(
        "(?i)\\b(summarize|summary|aggregate|count|how many|statistics?|overview)\\b");

    public String classify(String query) {
        if (T1.matcher(query).find()) return "T1";
        if (T2.matcher(query).find()) return "T2";
        if (T4.matcher(query).find()) return "T4";
        if (T3.matcher(query).find()) return "T3";
        return "T3"; // default: document search
    }

    public double confidence(String query, String templateId) {
        return switch (templateId) {
            case "T1" -> T1.matcher(query).find() ? 0.85 : 0.60;
            case "T2" -> T2.matcher(query).find() ? 0.85 : 0.60;
            case "T4" -> T4.matcher(query).find() ? 0.80 : 0.60;
            default -> T3.matcher(query).find() ? 0.75 : 0.55;
        };
    }
}
