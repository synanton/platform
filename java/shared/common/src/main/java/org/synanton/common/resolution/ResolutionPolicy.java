package org.synanton.common.resolution;

/** Deterministic, versioned policy. Bump strategyVersion on any prompt/model/threshold change. */
public interface ResolutionPolicy {
    String strategyVersion();

    ResolutionDecision decide(ResolutionContext context);
}
