package org.synanton.relix.identity;

import java.util.Optional;

/** Cassandra-authoritative accepted generation for a logical source. */
public interface SourceGenerationAuthority {

    Optional<Long> acceptedGeneration(SourceFenceKey key);
}
