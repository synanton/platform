package org.synanton.relix.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.relix.syntology.ActiveOntologyVersion;
import org.synanton.relix.syntology.OntologyLookupFailedException;
import org.synanton.relix.syntology.OntologyVersionUnavailableException;
import org.synanton.relix.syntology.SyntologyActiveVersionClient;
import org.synanton.relix.syntology.SyntologyAuthException;

import java.time.Clock;
import java.time.Instant;

public final class ProjectionProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ProjectionProvisioner.class);
    private static final String CANONICAL_GRAPH_ID = "canonical";
    private static final int MAX_ATTEMPTS = 2;

    private final CanonicalGraphMetadataStore store;
    private final SyntologyActiveVersionClient syntology;
    private final Clock clock;

    public ProjectionProvisioner(CanonicalGraphMetadataStore store,
                                 SyntologyActiveVersionClient syntology) {
        this(store, syntology, Clock.systemUTC());
    }

    public ProjectionProvisioner(CanonicalGraphMetadataStore store,
                                 SyntologyActiveVersionClient syntology,
                                 Clock clock) {
        this.store = store;
        this.syntology = syntology;
        this.clock = clock;
    }

    public ProjectionProvisioningResult provision(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ProjectionProvisioningResult.failed("tenantId is required");
        }

        ActiveOntologyVersion active;
        try {
            active = syntology.getActiveVersion(tenantId);
        } catch (SyntologyAuthException | OntologyVersionUnavailableException e) {
            log.error("graph.provisioning.failed tenant={} reason={}", tenantId, e.toString());
            return ProjectionProvisioningResult.failed(e.getMessage());
        } catch (OntologyLookupFailedException e) {
            log.error("graph.provisioning.failed tenant={} reason=syntology_unavailable", tenantId);
            return ProjectionProvisioningResult.failed("Syntology unavailable");
        } catch (RuntimeException e) {
            log.error("graph.provisioning.failed tenant={} reason={}", tenantId, e.toString());
            return ProjectionProvisioningResult.failed(e.getMessage());
        }
        if (active == null || active.activeVersion() == null || active.activeVersion().isBlank()) {
            log.error("graph.provisioning.failed tenant={} reason=no_active_version", tenantId);
            return ProjectionProvisioningResult.failed("Syntology returned no active ontology version");
        }

        Instant now = clock.instant();
        CanonicalGraphMetadata candidate = new CanonicalGraphMetadata(
                tenantId, CANONICAL_GRAPH_ID, active.activeVersion(), now, now, now);

        ProvisionResult provisioned = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                provisioned = store.provision(candidate);
                break;
            } catch (MetadataWriteConflictException e) {
                log.warn("graph.provisioning.write_conflict tenant={} attempt={}", tenantId, attempt);
            } catch (RuntimeException e) {
                log.error("graph.provisioning.failed tenant={} reason={}", tenantId, e.toString());
                return ProjectionProvisioningResult.failed(e.getMessage());
            }
        }
        if (provisioned == null) {
            log.error("graph.provisioning.failed tenant={} reason=ambiguous_metadata", tenantId);
            return ProjectionProvisioningResult.failed("Canonical graph metadata provisioning remained ambiguous");
        }

        CanonicalGraphMetadata metadata = provisioned.metadata();
        if (!active.activeVersion().equals(metadata.projectionVersion())) {
            log.warn("graph.provisioning.DIVERGENCE_DETECTED tenant={} active_version={} projection_version={}",
                    tenantId, active.activeVersion(), metadata.projectionVersion());
            return new ProjectionProvisioningResult(
                    ProjectionProvisioningOutcome.DIVERGENT, metadata,
                    "active ontology version differs from the existing projection version");
        }
        if (provisioned.created()) {
            log.info("graph.provisioning.newly_provisioned tenant={} version={}",
                    tenantId, metadata.projectionVersion());
            return new ProjectionProvisioningResult(
                    ProjectionProvisioningOutcome.NEWLY_PROVISIONED, metadata, null);
        }
        log.info("graph.provisioning.already_initialized tenant={} version={}",
                tenantId, metadata.projectionVersion());
        return new ProjectionProvisioningResult(
                ProjectionProvisioningOutcome.ALREADY_INITIALIZED, metadata, null);
    }
}
