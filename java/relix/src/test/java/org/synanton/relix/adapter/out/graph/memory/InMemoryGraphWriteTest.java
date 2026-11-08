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
