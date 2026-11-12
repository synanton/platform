package org.synanton.relix.adapter.out.metadata;

import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.identity.CanonicalGraphMetadata;
import org.synanton.relix.identity.CanonicalGraphMetadataStore;
import org.synanton.relix.identity.ProvisionResult;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCanonicalGraphMetadataStore implements CanonicalGraphMetadataStore {

    private final GraphConnector graphConnector;
    private final Map<String, CanonicalGraphMetadata> metadata = new ConcurrentHashMap<>();

    public InMemoryCanonicalGraphMetadataStore(GraphConnector graphConnector) {
        this.graphConnector = graphConnector;
    }

    @Override
    public Optional<CanonicalGraphMetadata> find(String tenantId) {
        return Optional.ofNullable(metadata.get(tenantId));
    }

    @Override
    public CanonicalGraphMetadata bootstrapIfAbsent(CanonicalGraphMetadata candidate) {
        return metadata.compute(candidate.tenantId(), (tenantId, current) -> {
            if (current == null) {
                return candidate;
            }
            return new CanonicalGraphMetadata(
                    current.tenantId(), current.graphId(), current.projectionVersion(),
                    current.syncedAt(), current.createdAt(), candidate.updatedAt());
        });
    }

    @Override
    public ProvisionResult provision(CanonicalGraphMetadata candidate) {
        boolean[] created = {false};
        CanonicalGraphMetadata result = metadata.compute(candidate.tenantId(), (tenantId, current) -> {
            if (current == null) {
                created[0] = true;
                return candidate;
            }
            return new CanonicalGraphMetadata(
                    current.tenantId(), current.graphId(), current.projectionVersion(),
                    candidate.syncedAt(), current.createdAt(), candidate.updatedAt());
        });
        return new ProvisionResult(result, created[0]);
    }

    @Override
    public boolean hasCanonicalData(String tenantId) {
        return graphConnector.stats(tenantId).entityCount() > 0;
    }
}
