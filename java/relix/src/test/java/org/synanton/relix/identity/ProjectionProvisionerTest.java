package org.synanton.relix.identity;

import org.junit.jupiter.api.Test;
import org.synanton.relix.syntology.ActiveOntologyVersion;
import org.synanton.relix.syntology.OntologyLookupFailedException;
import org.synanton.relix.syntology.SyntologyActiveVersionClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionProvisionerTest {

    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TENANT = "tenant-provision";

    @Test
    void obtainsAuthorityFromSyntologyAndCreatesMetadata() {
        FakeClient client = new FakeClient(active("v1"));
        FakeStore store = new FakeStore();

        ProjectionProvisioningResult result = provisioner(store, client).provision(TENANT);

        assertThat(client.calls).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo(ProjectionProvisioningOutcome.NEWLY_PROVISIONED);
        assertThat(result.metadata().projectionVersion()).isEqualTo("v1");
        assertThat(store.find(TENANT)).isPresent();
    }

    @Test
    void repeatedProvisioningIsAlreadyInitializedAndPreservesCreatedAt() {
        FakeClient client = new FakeClient(active("v1"));
        FakeStore store = new FakeStore();
        ProjectionProvisioner provisioner = provisioner(store, client);
        CanonicalGraphMetadata first = provisioner.provision(TENANT).metadata();

        ProjectionProvisioningResult second = provisioner.provision(TENANT);

        assertThat(second.outcome()).isEqualTo(ProjectionProvisioningOutcome.ALREADY_INITIALIZED);
        assertThat(second.metadata().createdAt()).isEqualTo(first.createdAt());
        assertThat(second.metadata().projectionVersion()).isEqualTo("v1");
    }

    @Test
    void syntologyFailureDoesNotWriteMetadata() {
        FakeClient client = new FakeClient(null);
        client.failure = new OntologyLookupFailedException("503", null);
        FakeStore store = new FakeStore();

        ProjectionProvisioningResult result = provisioner(store, client).provision(TENANT);

        assertThat(result.outcome()).isEqualTo(ProjectionProvisioningOutcome.FAILED);
        assertThat(store.find(TENANT)).isEmpty();
    }

    @Test
    void divergenceIsReportedWithoutOverwritingExistingVersion() {
        FakeClient client = new FakeClient(active("v2"));
        FakeStore store = new FakeStore();
        store.metadata.put(TENANT, metadata("v1"));

        ProjectionProvisioningResult result = provisioner(store, client).provision(TENANT);

        assertThat(result.outcome()).isEqualTo(ProjectionProvisioningOutcome.DIVERGENT);
        assertThat(store.find(TENANT).orElseThrow().projectionVersion()).isEqualTo("v1");
        assertThat(result.metadata().syncedAt()).isEqualTo(NOW);
    }

    @Test
    void outcomesAreDistinctAndObservable() {
        FakeClient client = new FakeClient(active("v1"));
        FakeStore store = new FakeStore();

        assertThat(provisioner(store, client).provision(TENANT).outcome())
                .isEqualTo(ProjectionProvisioningOutcome.NEWLY_PROVISIONED);
        assertThat(provisioner(store, client).provision(TENANT).outcome())
                .isEqualTo(ProjectionProvisioningOutcome.ALREADY_INITIALIZED);

        client.active = active("v2");
        assertThat(provisioner(store, client).provision(TENANT).outcome())
                .isEqualTo(ProjectionProvisioningOutcome.DIVERGENT);

        client.failure = new OntologyLookupFailedException("503", null);
        assertThat(provisioner(store, client).provision(TENANT).outcome())
                .isEqualTo(ProjectionProvisioningOutcome.FAILED);
    }

    private static ProjectionProvisioner provisioner(FakeStore store, FakeClient client) {
        return new ProjectionProvisioner(store, client, CLOCK);
    }

    private static ActiveOntologyVersion active(String version) {
        return new ActiveOntologyVersion(TENANT, version, "uuid:" + version, NOW);
    }

    private static CanonicalGraphMetadata metadata(String version) {
        return new CanonicalGraphMetadata(TENANT, "canonical", version, NOW, NOW, NOW);
    }

    private static final class FakeClient implements SyntologyActiveVersionClient {
        private ActiveOntologyVersion active;
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

    private static final class FakeStore implements CanonicalGraphMetadataStore {
        private final Map<String, CanonicalGraphMetadata> metadata = new ConcurrentHashMap<>();

        @Override
        public Optional<CanonicalGraphMetadata> find(String tenantId) {
            return Optional.ofNullable(metadata.get(tenantId));
        }

        @Override
        public CanonicalGraphMetadata bootstrapIfAbsent(CanonicalGraphMetadata candidate) {
            return metadata.computeIfAbsent(candidate.tenantId(), ignored -> candidate);
        }

        @Override
        public ProvisionResult provision(CanonicalGraphMetadata candidate) {
            boolean[] created = {false};
            CanonicalGraphMetadata value = metadata.compute(candidate.tenantId(), (tenant, current) -> {
                if (current == null) {
                    created[0] = true;
                    return candidate;
                }
                return new CanonicalGraphMetadata(current.tenantId(), current.graphId(),
                        current.projectionVersion(), candidate.syncedAt(), current.createdAt(),
                        candidate.updatedAt());
            });
            return new ProvisionResult(value, created[0]);
        }

        @Override
        public boolean hasCanonicalData(String tenantId) {
            return metadata.containsKey(tenantId);
        }
    }
}
