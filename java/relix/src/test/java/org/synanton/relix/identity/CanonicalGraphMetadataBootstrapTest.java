package org.synanton.relix.identity;

import org.junit.jupiter.api.Test;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.relix.syntology.ActiveOntologyVersion;
import org.synanton.relix.syntology.OntologyLookupFailedException;
import org.synanton.relix.syntology.OntologyVersionUnavailableException;
import org.synanton.relix.syntology.SyntologyActiveVersionClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalGraphMetadataBootstrapTest {

    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void returnsExistingMetadataWithoutCallingSyntology() {
        FakeStore store = new FakeStore();
        store.metadata = metadata("v1.2.0");
        FakeClient client = new FakeClient(active("v9.9.9"));

        GraphIdentity identity = bootstrap(store, client).bootstrap("tenant-a");

        assertThat(identity).isEqualTo(GraphIdentity.canonical("tenant-a", "v1.2.0"));
        assertThat(client.calls).isZero();
        assertThat(store.bootstrapCalls).isZero();
    }

    @Test
    void rejectsNonCanonicalGraphId() {
        FakeStore store = new FakeStore();
        store.metadata = new CanonicalGraphMetadata("tenant-a", "other", "v1.2.0",
                NOW, NOW, NOW);

        assertThatThrownBy(() -> bootstrap(store, new FakeClient(active("v1.2.0")))
                .bootstrap("tenant-a"))
                .isInstanceOf(UnsupportedGraphIdException.class);
    }

    @Test
    void failsClosedWhenCanonicalDataIsMissing() {
        FakeStore store = new FakeStore();
        store.hasData = false;
        FakeClient client = new FakeClient(active("v1.2.0"));

        assertThatThrownBy(() -> bootstrap(store, client).bootstrap("tenant-a"))
                .isInstanceOf(MissingCanonicalGraphException.class);
        assertThat(client.calls).isZero();
    }

    @Test
    void bootstrapsFromSyntologyAndCachesMetadata() {
        FakeStore store = new FakeStore();
        store.hasData = true;
        FakeClient client = new FakeClient(active("v1.2.0"));
        CanonicalGraphMetadataBootstrap bootstrap = bootstrap(store, client);

        GraphIdentity first = bootstrap.bootstrap("tenant-a");
        GraphIdentity second = bootstrap.bootstrap("tenant-a");

        assertThat(first).isEqualTo(GraphIdentity.canonical("tenant-a", "v1.2.0"));
        assertThat(second).isEqualTo(first);
        assertThat(client.calls).isEqualTo(1);
        assertThat(store.bootstrapCalls).isEqualTo(1);
        assertThat(store.metadata.projectionVersion()).isEqualTo("v1.2.0");
        assertThat(store.metadata.syncedAt()).isEqualTo(NOW);
        assertThat(store.metadata.createdAt()).isEqualTo(NOW);
    }

    @Test
    void preservesExistingMetadataVersionWhenBootstrapRaces() {
        FakeStore store = new FakeStore();
        store.hasData = true;
        store.raceMetadata = metadata("v1.1.0");
        FakeClient client = new FakeClient(active("v1.2.0"));

        GraphIdentity identity = bootstrap(store, client).bootstrap("tenant-a");

        assertThat(identity).isEqualTo(GraphIdentity.canonical("tenant-a", "v1.1.0"));
        assertThat(store.metadata.projectionVersion()).isEqualTo("v1.1.0");
    }

    @Test
    void propagatesUnavailableActiveVersion() {
        FakeStore store = new FakeStore();
        store.hasData = true;
        FakeClient client = new FakeClient(null);
        client.failure = new OntologyVersionUnavailableException("no active ontology");

        assertThatThrownBy(() -> bootstrap(store, client).bootstrap("tenant-a"))
                .isInstanceOf(OntologyVersionUnavailableException.class);
    }

    @Test
    void propagatesRetryableSyntologyFailure() {
        FakeStore store = new FakeStore();
        store.hasData = true;
        FakeClient client = new FakeClient(null);
        client.failure = new OntologyLookupFailedException("syntology down", null);

        assertThatThrownBy(() -> bootstrap(store, client).bootstrap("tenant-a"))
                .isInstanceOf(OntologyLookupFailedException.class);
    }

    @Test
    void rejectsBlankActiveVersion() {
        FakeStore store = new FakeStore();
        store.hasData = true;
        FakeClient client = new FakeClient(new ActiveOntologyVersion(
                "tenant-a", " ", "uuid:1", NOW));

        assertThatThrownBy(() -> bootstrap(store, client).bootstrap("tenant-a"))
                .isInstanceOf(OntologyVersionUnavailableException.class);
    }

    @Test
    void retriesOnceAndReportsAmbiguousBootstrap() {
        FakeStore store = new FakeStore();
        store.hasData = true;
        store.conflictsRemaining = 2;

        assertThatThrownBy(() -> bootstrap(store, new FakeClient(active("v1.2.0")))
                .bootstrap("tenant-a"))
                .isInstanceOf(AmbiguousCanonicalGraphException.class);
        assertThat(store.bootstrapCalls).isEqualTo(2);
    }

    @Test
    void retriesOnceAndSucceeds() {
        FakeStore store = new FakeStore();
        store.hasData = true;
        store.conflictsRemaining = 1;

        GraphIdentity identity = bootstrap(store, new FakeClient(active("v1.2.0")))
                .bootstrap("tenant-a");

        assertThat(identity).isEqualTo(GraphIdentity.canonical("tenant-a", "v1.2.0"));
        assertThat(store.bootstrapCalls).isEqualTo(2);
    }

    private static CanonicalGraphMetadataBootstrap bootstrap(FakeStore store, FakeClient client) {
        return new CanonicalGraphMetadataBootstrap(store, client, CLOCK);
    }

    private static ActiveOntologyVersion active(String version) {
        return new ActiveOntologyVersion("tenant-a", version, "uuid:1", NOW);
    }

    private static CanonicalGraphMetadata metadata(String version) {
        return new CanonicalGraphMetadata("tenant-a", "canonical", version, NOW, NOW, NOW);
    }

    private static final class FakeStore implements CanonicalGraphMetadataStore {
        private CanonicalGraphMetadata metadata;
        private CanonicalGraphMetadata raceMetadata;
        private boolean hasData;
        private int bootstrapCalls;
        private int conflictsRemaining;

        @Override
        public Optional<CanonicalGraphMetadata> find(String tenantId) {
            return Optional.ofNullable(metadata);
        }

        @Override
        public CanonicalGraphMetadata bootstrapIfAbsent(CanonicalGraphMetadata candidate) {
            bootstrapCalls++;
            if (conflictsRemaining > 0) {
                conflictsRemaining--;
                throw new MetadataWriteConflictException("race", null);
            }
            if (raceMetadata != null) {
                metadata = raceMetadata;
                raceMetadata = null;
            }
            if (metadata == null) {
                metadata = candidate;
            }
            return metadata;
        }

        @Override
        public boolean hasCanonicalData(String tenantId) {
            return hasData;
        }
    }

    private static final class FakeClient implements SyntologyActiveVersionClient {
        private final ActiveOntologyVersion active;
        private RuntimeException failure;
        private int calls;

        private FakeClient(ActiveOntologyVersion active) {
            this.active = active;
        }

        @Override
        public ActiveOntologyVersion getActiveVersion(String tenantId) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return active;
        }
    }
}
