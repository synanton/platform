package org.synanton.synquest.api;

import java.util.Objects;
import org.synanton.storage.contract.GenerationId;

/**
 * Rebuild target. A rebuild always produces a new generation fully derived from
 * authoritative Synvault state (invariant 35).
 */
public record RebuildOptions(GenerationId targetGeneration, boolean full) {
    public RebuildOptions {
        Objects.requireNonNull(targetGeneration, "targetGeneration");
    }
}
