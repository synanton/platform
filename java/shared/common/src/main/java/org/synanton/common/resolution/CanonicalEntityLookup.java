package org.synanton.common.resolution;

import java.util.Optional;

/**
 * Source-of-truth verification for a Lucene entity candidate (Relix/Neo4j).
 * Empty means the entity no longer exists / is inactive; an unreachable store
 * must throw a {@link RetryableProcessingException}, never look like "no match".
 */
public interface CanonicalEntityLookup {
    Optional<String> getActiveEntityType(String tenantId, String entityId);
}
