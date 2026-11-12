package org.synanton.relix.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.relix.syntology.ActiveOntologyVersion;
import org.synanton.relix.syntology.SyntologyActiveVersionClient;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

public final class CanonicalGraphMetadataBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CanonicalGraphMetadataBootstrap.class);
    private static final String CANONICAL_GRAPH_ID = "canonical";
    private static final int MAX_ATTEMPTS = 2;

    private final CanonicalGraphMetadataStore store;
    private final SyntologyActiveVersionClient syntology;
    private final Clock clock;

    public CanonicalGraphMetadataBootstrap(CanonicalGraphMetadataStore store,
                                           SyntologyActiveVersionClient syntology) {
        this(store, syntology, Clock.systemUTC());
    }

    public CanonicalGraphMetadataBootstrap(CanonicalGraphMetadataStore store,
                                           SyntologyActiveVersionClient syntology,
                                           Clock clock) {
        this.store = store;
        this.syntology = syntology;
        this.clock = clock;
    }

    public GraphIdentity bootstrap(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }

        Optional<CanonicalGraphMetadata> existing = store.find(tenantId);
        if (existing.isPresent()) {
            return identity(existing.get());
        }

        if (!store.hasCanonicalData(tenantId)) {
            throw new MissingCanonicalGraphException(
                    "No canonical graph data exists for tenant " + tenantId);
        }

        ActiveOntologyVersion active = syntology.getActiveVersion(tenantId);
        if (active == null || active.activeVersion() == null || active.activeVersion().isBlank()) {
            throw new org.synanton.relix.syntology.OntologyVersionUnavailableException(
                    "Syntology returned no active ontology version for tenant " + tenantId);
        }

        Instant now = clock.instant();
        CanonicalGraphMetadata candidate = new CanonicalGraphMetadata(
                tenantId, CANONICAL_GRAPH_ID, active.activeVersion(), now, now, now);

        CanonicalGraphMetadata metadata = null;
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                metadata = store.bootstrapIfAbsent(candidate);
                break;
            } catch (MetadataWriteConflictException e) {
                lastFailure = e;
            }
        }

        if (metadata == null) {
            RuntimeException failure = lastFailure;
            metadata = store.find(tenantId).orElseThrow(() -> new AmbiguousCanonicalGraphException(
                    "Canonical graph metadata bootstrap remained ambiguous for tenant " + tenantId,
                    failure));
        }

        if (!active.activeVersion().equals(metadata.projectionVersion())) {
            log.warn("graph.identity.bootstrap.version_diverged tenant={} requested={} stored={}",
                    tenantId, active.activeVersion(), metadata.projectionVersion());
        }
        return identity(metadata);
    }

    private GraphIdentity identity(CanonicalGraphMetadata metadata) {
        if (!CANONICAL_GRAPH_ID.equals(metadata.graphId())) {
            throw new UnsupportedGraphIdException(
                    "Unsupported graph id for tenant " + metadata.tenantId() + ": "
                            + metadata.graphId());
        }
        return metadata.identity();
    }
}
