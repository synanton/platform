package org.synanton.relix.adapter.out.metadata;

import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.identity.CanonicalGraphMetadata;
import org.synanton.relix.identity.CanonicalGraphMetadataStore;

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
    public boolean hasCanonicalData(String tenantId) {
        return graphConnector.stats(tenantId).entityCount() > 0;
    }
}
