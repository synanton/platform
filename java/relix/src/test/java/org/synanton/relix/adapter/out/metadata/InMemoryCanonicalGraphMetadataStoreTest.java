package org.synanton.relix.adapter.out.metadata;

import org.junit.jupiter.api.Test;
import org.synanton.relix.api.dto.GraphStats;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.identity.CanonicalGraphMetadata;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InMemoryCanonicalGraphMetadataStoreTest {

    private static final Instant CREATED = Instant.parse("2026-11-11T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-11-12T00:00:00Z");

    @Test
    void preservesProjectionVersionOnMatch() {
        GraphConnector connector = mock(GraphConnector.class);
        when(connector.stats("tenant-a"))
                .thenReturn(new GraphStats("tenant-a", 1, 0, 1, 1));
        InMemoryCanonicalGraphMetadataStore store =
                new InMemoryCanonicalGraphMetadataStore(connector);

        CanonicalGraphMetadata first = store.bootstrapIfAbsent(new CanonicalGraphMetadata(
                "tenant-a", "canonical", "v1.2.0", CREATED, CREATED, CREATED));
        CanonicalGraphMetadata second = store.bootstrapIfAbsent(new CanonicalGraphMetadata(
                "tenant-a", "canonical", "v9.9.9", UPDATED, UPDATED, UPDATED));

        assertThat(first.projectionVersion()).isEqualTo("v1.2.0");
        assertThat(second.projectionVersion()).isEqualTo("v1.2.0");
        assertThat(second.createdAt()).isEqualTo(CREATED);
        assertThat(second.updatedAt()).isEqualTo(UPDATED);
        assertThat(store.find("tenant-a")).contains(second);
        assertThat(store.hasCanonicalData("tenant-a")).isTrue();
    }

    @Test
    void reportsMissingMetadataAndData() {
        GraphConnector connector = mock(GraphConnector.class);
        when(connector.stats("tenant-a"))
                .thenReturn(new GraphStats("tenant-a", 0, 0, 0, -1));
        InMemoryCanonicalGraphMetadataStore store =
                new InMemoryCanonicalGraphMetadataStore(connector);

        assertThat(store.find("tenant-a")).isEmpty();
        assertThat(store.hasCanonicalData("tenant-a")).isFalse();
    }
}
