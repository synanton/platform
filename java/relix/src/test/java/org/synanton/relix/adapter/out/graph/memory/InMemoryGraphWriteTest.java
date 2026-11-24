package org.synanton.relix.adapter.out.graph.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.synanton.relix.graph.ExtractedMention;
import org.synanton.relix.graph.ExtractedRelation;
import org.synanton.relix.graph.GraphLoader;

class InMemoryGraphWriteTest {

    @Test
    void documentGraphIsScopedAndCarriesGeneration() {
        InMemoryGraphConnector connector = new InMemoryGraphConnector(mock(GraphLoader.class));
        Instant now = Instant.now();
        ExtractedMention subject = new ExtractedMention("m1", "t", "v1", "c", "c:0",
                0, 1, 0, 1, "A", "Concept", now, "gen-1");
        ExtractedMention object = new ExtractedMention("m2", "t", "v1", "c", "c:0",
                2, 3, 2, 3, "B", "Concept", now, "gen-1");
        ExtractedRelation relation = new ExtractedRelation("r1", "t", "v1", "uses", "m1", "m2",
                "c:0", 0, 3, 0, 3, now, null, "v1", 1, null, "gen-1");
        ExtractedMention sibling = new ExtractedMention("m3", "t", "v2", "c2", "c2:0",
                0, 1, 0, 1, "C", "Concept", now, "gen-2");

        connector.upsertMentions(List.of(subject, object, sibling));
        connector.upsertRelations(List.of(relation));

        var graph = connector.documentGraph("t", "v1");
        assertThat(graph.documentId()).isEqualTo("v1");
        assertThat(graph.nodes()).extracting(node -> node.mentionId())
                .containsExactly("m1", "m2");
        assertThat(graph.nodes()).allSatisfy(node -> {
            assertThat(node.resolutionState()).isEqualTo("UNRESOLVED");
            assertThat(node.extractionGeneration()).isEqualTo("gen-1");
        });
        assertThat(graph.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.subjectMentionId()).isEqualTo("m1");
            assertThat(edge.objectMentionId()).isEqualTo("m2");
            assertThat(edge.promotionState()).isEqualTo("UNPROMOTED");
            assertThat(edge.extractionGeneration()).isEqualTo("gen-1");
        });
        assertThat(connector.documentGraph("t", "v2").nodes()).extracting(node -> node.mentionId())
                .containsExactly("m3");
        assertThat(connector.documentGraph("other", "v1").nodes()).isEmpty();
    }

    @Test
    void upsertsMentionsAndRelationsIdempotently() {
        InMemoryGraphConnector connector = new InMemoryGraphConnector(mock(GraphLoader.class));
        Instant now = Instant.now();
        ExtractedMention subject = new ExtractedMention("m1", "t", "v", "c", "c:0",
                0, 9, 0, 9, "A", "Concept", now);
        ExtractedMention object = new ExtractedMention("m2", "t", "v", "c", "c:0",
                10, 20, 10, 20, "B", "Concept", now);
        ExtractedRelation relation = new ExtractedRelation("r1", "t", "v", "uses", "m1", "m2",
                "c:0", 0, 20, 0, 20, now);

        connector.upsertMentions(List.of(subject, object));
        connector.upsertMentions(List.of(subject, object));
        connector.upsertRelations(List.of(relation));
        connector.upsertRelations(List.of(relation));

        assertThat(connector.extractedMentionCount("t")).isEqualTo(2);
        assertThat(connector.extractedRelationCount("t")).isEqualTo(1);
    }
}
