package org.synanton.eventing.contract;

/** Replay classification frozen by Design 1.27 §39. This applies to durable events, not commands. */
public enum ReplayClass {
    REPLAY_SAFE,
    REPLAY_WITH_POLICY,
    NOT_REPLAYABLE
}
