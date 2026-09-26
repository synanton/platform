package org.synanton.synquest.api;

import java.util.Objects;
import org.synanton.storage.contract.GenerationId;

/** Observable index state for §8.3 build/rebuild status reporting. */
public record IndexStatus(GenerationId activeGeneration, long projectionCount, boolean healthy) {
    public IndexStatus {
        Objects.requireNonNull(activeGeneration, "activeGeneration");
    }
}
