package org.synanton.relix.graph;

import org.junit.jupiter.api.Test;
import org.synanton.common.resolution.GraphCapabilities;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.common.resolution.StaleResolutionException;
import org.synanton.relix.api.dto.Entity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public abstract class AbstractGraphContractTest {

    protected abstract GraphReadPort readPort();

    protected abstract GraphWritePort writePort();

    protected abstract GraphCapabilities capabilities();

    protected void writeAtomicallyAndFail(GraphIdentity identity, Entity first, Entity second) {
        throw new UnsupportedOperationException("adapter did not provide an atomic-failure hook");
    }

    protected void writeWithFence(GraphIdentity identity, Entity entity, long fencingToken) {
        throw new UnsupportedOperationException("adapter did not provide a conditional-write hook");
    }

    @Test
    void tenantIsolation() {
        GraphIdentity tenantA = GraphIdentity.canonical(tenant("isolation-a"), "v1");
        GraphIdentity tenantB = GraphIdentity.canonical(tenant("isolation-b"), "v1");
        Entity entity = entity(UUID.randomUUID(), "Isolated", "Thing");

        writePort().upsertEntity(tenantA, entity);

        assertThat(readPort().getEntity(tenantA, entity.entityId().toString())).isPresent();
        assertThat(readPort().getEntity(tenantB, entity.entityId().toString())).isEmpty();
    }

    @Test
    void nonCanonicalGraphIdRejected() {
        GraphIdentity identity = new GraphIdentity(tenant("non-canonical"), "annotations", "v1");

        assertThatThrownBy(() -> readPort().getEntity(identity, UUID.randomUUID().toString()))
                .isInstanceOf(org.synanton.relix.identity.UnsupportedGraphIdException.class);
    }

    @Test
    void capabilityImplicationsAreConsistent() {
        assertThat(capabilities().compositionIsConsistent()).isTrue();
        if (capabilities().supportsAtomicMultiNodeWrite()) {
            assertThat(capabilities().supportsConditionalWrites()).isTrue();
        }
    }

    @Test
    void atomicMultiNodeWriteRollsBackOnFailure() {
        assumeTrue(capabilities().supportsAtomicMultiNodeWrite());
        GraphIdentity identity = GraphIdentity.canonical(tenant("atomic"), "v1");
        Entity first = entity(UUID.randomUUID(), "Atomic First", "Thing");
        Entity second = entity(first.entityId(), "Atomic Second", "Thing");

        assertThatThrownBy(() -> writeAtomicallyAndFail(identity, first, second))
                .isInstanceOf(RuntimeException.class);

        assertThat(readPort().getEntity(identity, first.entityId().toString())).isEmpty();
    }

    @Test
    void conditionalWriteRejectsStaleToken() {
        assumeTrue(capabilities().supportsConditionalWrites());
        GraphIdentity identity = GraphIdentity.canonical(tenant("fencing"), "v1");
        UUID id = UUID.randomUUID();
        writeWithFence(identity, entity(id, "Fence v1", "Thing"), 1);

        assertThatThrownBy(() -> writeWithFence(identity, entity(id, "Fence stale", "Thing"), 0))
                .isInstanceOf(StaleResolutionException.class);

        assertThat(readPort().getEntity(identity, id.toString()))
                .get().extracting(Entity::label).isEqualTo("Fence v1");
    }

    @Test
    void idempotentUpsertDoesNotDuplicate() {
        assumeTrue(capabilities().supportsIdempotentUpsert());
        GraphIdentity identity = GraphIdentity.canonical(tenant("idempotent"), "v1");
        Entity entity = entity(UUID.randomUUID(), "Idempotent", "Thing");

        writePort().upsertEntity(identity, entity);
        writePort().upsertEntity(identity, entity);

        assertThat(readPort().lookupEntities(identity, "Idempotent", "Thing", 10).entities())
                .hasSize(1);
    }

    @Test
    void readAfterWriteIsVisible() {
        assumeTrue(capabilities().supportsReadAfterWrite());
        GraphIdentity identity = GraphIdentity.canonical(tenant("read-after-write"), "v1");
        Entity entity = entity(UUID.randomUUID(), "Visible", "Thing");

        writePort().upsertEntity(identity, entity);

        assertThat(readPort().getEntity(identity, entity.entityId().toString()))
                .get().extracting(Entity::label).isEqualTo("Visible");
    }

    protected static Entity entity(UUID id, String label, String type) {
        return new Entity(id, label, type, 1.0, List.of());
    }

    private static String tenant(String suffix) {
        return "contract-" + suffix + "-" + UUID.randomUUID();
    }
}
