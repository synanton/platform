package org.synanton.relix.identity;

import org.junit.jupiter.api.Test;
import org.synanton.common.resolution.GraphIdentity;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CanonicalGraphIdentityResolverTest {

    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    private final CanonicalGraphMetadataStore store = mock(CanonicalGraphMetadataStore.class);
    private final CanonicalGraphMetadataBootstrap bootstrap =
            mock(CanonicalGraphMetadataBootstrap.class);
    private final CanonicalGraphIdentityResolver resolver =
            new CanonicalGraphIdentityResolver(store, bootstrap);

    @Test
    void readsMetadataWithoutCallingSyntologyOrBootstrap() {
        when(store.find("tenant-a")).thenReturn(Optional.of(metadata("v1.2.0")));

        GraphIdentity identity = resolver.resolveCanonical("tenant-a");

        assertThat(identity).isEqualTo(GraphIdentity.canonical("tenant-a", "v1.2.0"));
        verifyNoInteractions(bootstrap);
    }

    @Test
    void bootstrapsOnlyWhenMetadataIsMissing() {
        when(store.find("tenant-a")).thenReturn(Optional.empty());
        GraphIdentity expected = GraphIdentity.canonical("tenant-a", "v1.2.0");
        when(bootstrap.bootstrap("tenant-a")).thenReturn(expected);

        assertThat(resolver.resolveCanonical("tenant-a")).isEqualTo(expected);
    }

    @Test
    void rejectsNonCanonicalMetadata() {
        when(store.find("tenant-a")).thenReturn(Optional.of(
                new CanonicalGraphMetadata("tenant-a", "other", "v1.2.0", NOW, NOW, NOW)));

        assertThatThrownBy(() -> resolver.resolveCanonical("tenant-a"))
                .isInstanceOf(UnsupportedGraphIdException.class);
        verifyNoInteractions(bootstrap);
    }

    @Test
    void rejectsBlankTenant() {
        assertThatThrownBy(() -> resolver.resolveCanonical(" "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store, bootstrap);
    }

    private static CanonicalGraphMetadata metadata(String version) {
        return new CanonicalGraphMetadata("tenant-a", "canonical", version, NOW, NOW, NOW);
    }
}
