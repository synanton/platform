package org.synanton.synflux.extraction.ontology;

import java.util.Optional;

/** Resolves the active ontology release for a tenant. */
@FunctionalInterface
public interface OntologyReleaseResolver {
    Optional<OntologyRelease> resolve(String tenantId);
}
