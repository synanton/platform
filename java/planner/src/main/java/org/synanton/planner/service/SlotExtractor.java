package org.synanton.planner.service;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.*;

@Component
public class SlotExtractor {
    private static final Pattern ENTITY_PATTERN = Pattern.compile("(?i)\\b(about|for|of|on|regarding)\\s+([A-Z][A-Za-z0-9\\s\\-]{1,40})");
    private static final Pattern K_PATTERN = Pattern.compile("\\b(top|first)\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOP_PATTERN = Pattern.compile("\\b(\\d+)\\s*hops?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCOPE_PATTERN = Pattern.compile("\\b(documents?|chunks?|graph|entities)\\b", Pattern.CASE_INSENSITIVE);

    public Map<String, Object> extract(String query, Set<String> knownLabels) {
        Map<String, Object> slots = new LinkedHashMap<>();

        // Extract entity name
        Matcher em = ENTITY_PATTERN.matcher(query);
        if (em.find()) {
            String candidate = em.group(2).trim();
            // Match against known labels
            String matched = knownLabels.stream()
                .filter(l -> l.equalsIgnoreCase(candidate) || l.toLowerCase().contains(candidate.toLowerCase()))
                .findFirst().orElse(candidate);
            slots.put("entity", matched);
        } else {
            // Fallback: take quoted text or capitalized words
            Matcher qm = Pattern.compile("\"([^\"]+)\"").matcher(query);
            if (qm.find()) slots.put("entity", qm.group(1));
        }

        // k (top-N)
        Matcher km = K_PATTERN.matcher(query);
        if (km.find()) slots.put("k", Integer.parseInt(km.group(2)));
        else slots.put("k", 10);

        // hop_depth
        Matcher hm = HOP_PATTERN.matcher(query);
        if (hm.find()) slots.put("hop_depth", Integer.parseInt(hm.group(1)));

        // scope
        Matcher sm = SCOPE_PATTERN.matcher(query);
        if (sm.find()) slots.put("scope", sm.group(1).toLowerCase());

        return slots;
    }
}
