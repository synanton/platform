package org.synanton.relix.adapter.out.metadata;

import org.junit.jupiter.api.Test;
import org.synanton.relix.adapter.out.graph.neo4j.CypherExecutor;
import org.synanton.relix.identity.SourceFenceKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Neo4jSourceFenceStoreTest {

    private final CypherExecutor cypher = mock(CypherExecutor.class);
    private final Neo4jSourceFenceStore store = new Neo4jSourceFenceStore(cypher);
    private final SourceFenceKey key = new SourceFenceKey("tenant-a", "source-a");

    @Test
    void readsExistingGeneration() {
        when(cypher.read(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of(Map.of("generation", 3L)));

        assertThat(store.read(key)).contains(3L);
    }

    @Test
    void advanceUsesMonotonicMergeQuery() {
        when(cypher.writeReturning(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of(Map.of("generation", 3L)));

        assertThat(store.advance(key, 3L).generation()).isEqualTo(3L);
    }
}
