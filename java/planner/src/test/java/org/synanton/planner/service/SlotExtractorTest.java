package org.synanton.planner.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SlotExtractorTest {

    private final SlotExtractor extractor = new SlotExtractor();

    @Test
    void extractsDefaultK() {
        Map<String, Object> slots = extractor.extract("what is Acme?", Set.of());
        assertThat(slots).containsEntry("k", 10);
    }

    @Test
    void extractsExplicitK() {
        Map<String, Object> slots = extractor.extract("show top 5 suppliers", Set.of());
        assertThat(slots).containsEntry("k", 5);
    }

    @Test
    void extractsHopDepth() {
        Map<String, Object> slots = extractor.extract("find nodes 2 hops from Acme", Set.of());
        assertThat(slots).containsEntry("hop_depth", 2);
    }

    @Test
    void extractsEntityFromKnownLabels() {
        Set<String> labels = Set.of("Acme Corp", "GlobalTech");
        Map<String, Object> slots = extractor.extract("tell me about Acme Corp", labels);
        assertThat(slots).containsKey("entity");
        assertThat(slots.get("entity").toString()).containsIgnoringCase("Acme");
    }

    @Test
    void extractsQuotedEntity() {
        Map<String, Object> slots = extractor.extract("find \"Project Alpha\" documents", Set.of());
        assertThat(slots).containsEntry("entity", "Project Alpha");
    }
}
