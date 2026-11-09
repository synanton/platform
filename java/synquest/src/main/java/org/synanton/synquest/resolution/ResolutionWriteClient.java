package org.synanton.synquest.resolution;

import org.synanton.common.resolution.ResolutionDecision;

/** Writes a resolution decision to Relix's transactional graph port. */
public interface ResolutionWriteClient {
    void upsertResolution(ResolutionDecision decision);
}
