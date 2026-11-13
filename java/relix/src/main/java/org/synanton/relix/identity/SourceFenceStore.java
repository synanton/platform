package org.synanton.relix.identity;

import java.util.Optional;

/** Neo4j-persisted source generation fence. */
public interface SourceFenceStore {

    Optional<Long> read(SourceFenceKey key);

    /** Monotonic, idempotent advance; never lowers an existing generation. */
    long advance(SourceFenceKey key, long generation);
}
