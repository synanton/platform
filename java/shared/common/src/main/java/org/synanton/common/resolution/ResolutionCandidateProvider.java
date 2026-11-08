package org.synanton.common.resolution;

import java.util.List;

/** Candidate generation port (implemented over the existing SearchEngineAdapter in 4b). */
public interface ResolutionCandidateProvider {
    List<ResolutionCandidate> candidates(ResolutionContext context);
}
